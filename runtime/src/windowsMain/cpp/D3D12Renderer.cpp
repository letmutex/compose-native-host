#include "D3D12Renderer.h"
#include <stdexcept>
#include <iostream>

#pragma comment(lib, "d3d12.lib")
#pragma comment(lib, "dxgi.lib")

D3D12Renderer::D3D12Renderer() {}

D3D12Renderer::~D3D12Renderer() {
    Shutdown();
}

bool D3D12Renderer::Initialize(HWND hwnd, int width, int height) {
    UINT dxgiFactoryFlags = 0;

#if defined(_DEBUG)
    // Enable D3D12 debug layer
    Microsoft::WRL::ComPtr<ID3D12Debug> debugController;
    if (SUCCEEDED(D3D12GetDebugInterface(IID_PPV_ARGS(&debugController)))) {
        debugController->EnableDebugLayer();
        dxgiFactoryFlags |= DXGI_CREATE_FACTORY_DEBUG;
    }
#endif

    Microsoft::WRL::ComPtr<IDXGIFactory4> factory;
    if (FAILED(CreateDXGIFactory2(dxgiFactoryFlags, IID_PPV_ARGS(&factory)))) {
        return false;
    }

    // Retrieve default adapter
    if (FAILED(factory->EnumAdapters1(0, &adapter_))) {
        return false;
    }

    // Create D3D12 Device
    if (FAILED(D3D12CreateDevice(adapter_.Get(), D3D_FEATURE_LEVEL_11_0, IID_PPV_ARGS(&device_)))) {
        return false;
    }

    // Create Command Queue
    D3D12_COMMAND_QUEUE_DESC queueDesc = {};
    queueDesc.Flags = D3D12_COMMAND_QUEUE_FLAG_NONE;
    queueDesc.Type = D3D12_COMMAND_LIST_TYPE_DIRECT;

    if (FAILED(device_->CreateCommandQueue(&queueDesc, IID_PPV_ARGS(&commandQueue_)))) {
        return false;
    }

    // Create Swap Chain
    DXGI_SWAP_CHAIN_DESC1 swapChainDesc = {};
    swapChainDesc.BufferCount = FrameCount;
    swapChainDesc.Width = width;
    swapChainDesc.Height = height;
    swapChainDesc.Format = DXGI_FORMAT_R8G8B8A8_UNORM;
    swapChainDesc.BufferUsage = DXGI_USAGE_RENDER_TARGET_OUTPUT;
    swapChainDesc.SwapEffect = DXGI_SWAP_EFFECT_FLIP_DISCARD;
    swapChainDesc.SampleDesc.Count = 1;
    // Do not add DXGI_SWAP_CHAIN_FLAG_FRAME_LATENCY_WAITABLE_OBJECT here.
    // The host's render signal can be caused by input or window events that Compose later
    // determines need no repaint. Waiting on that consumptive handle before that decision
    // can consume its signal without a Present to re-arm it, permanently stalling rendering.
    // A waitable-object implementation requires an explicit submission acknowledgement and
    // a separate display clock; DwmFlush remains the safe pacing mechanism for this host.

    Microsoft::WRL::ComPtr<IDXGISwapChain1> swapChain1;
    if (FAILED(factory->CreateSwapChainForHwnd(
            commandQueue_.Get(),
            hwnd,
            &swapChainDesc,
            nullptr,
            nullptr,
            &swapChain1
        ))) {
        return false;
    }

    if (FAILED(swapChain1.As(&swapChain_))) {
        return false;
    }

    frameIndex_ = swapChain_->GetCurrentBackBufferIndex();

    // Create Descriptor Heaps (RTV)
    D3D12_DESCRIPTOR_HEAP_DESC rtvHeapDesc = {};
    rtvHeapDesc.NumDescriptors = FrameCount;
    rtvHeapDesc.Type = D3D12_DESCRIPTOR_HEAP_TYPE_RTV;
    rtvHeapDesc.Flags = D3D12_DESCRIPTOR_HEAP_FLAG_NONE;
    if (FAILED(device_->CreateDescriptorHeap(&rtvHeapDesc, IID_PPV_ARGS(&rtvHeap_)))) {
        return false;
    }

    rtvDescriptorSize_ = device_->GetDescriptorHandleIncrementSize(D3D12_DESCRIPTOR_HEAP_TYPE_RTV);

    // Create Frame Resources (RTVs)
    D3D12_CPU_DESCRIPTOR_HANDLE rtvHandle = rtvHeap_->GetCPUDescriptorHandleForHeapStart();
    for (UINT n = 0; n < FrameCount; n++) {
        if (FAILED(swapChain_->GetBuffer(n, IID_PPV_ARGS(&renderTargets_[n])))) {
            return false;
        }
        device_->CreateRenderTargetView(renderTargets_[n].Get(), nullptr, rtvHandle);
        rtvHandle.ptr += rtvDescriptorSize_;
    }

    currentFenceValue_ = 0;
    for (UINT n = 0; n < FrameCount; n++) {
        fenceValues_[n] = currentFenceValue_;
    }

    // Create synchronization fence
    if (FAILED(device_->CreateFence(currentFenceValue_, D3D12_FENCE_FLAG_NONE, IID_PPV_ARGS(&fence_)))) {
        return false;
    }
    currentFenceValue_++;

    fenceEvent_ = CreateEvent(nullptr, FALSE, FALSE, nullptr);
    if (fenceEvent_ == nullptr) {
        return false;
    }

    width_ = width;
    height_ = height;

    return true;
}

bool D3D12Renderer::Resize(int width, int height) {
    if (!swapChain_) return false;
    if (width_ == width && height_ == height) return true;

    WaitForGpu();

    for (UINT n = 0; n < FrameCount; n++) {
        renderTargets_[n].Reset();
    }

    HRESULT hr = swapChain_->ResizeBuffers(FrameCount, width, height, DXGI_FORMAT_R8G8B8A8_UNORM, 0);
    if (FAILED(hr)) {
        std::cerr << "[D3D12] ResizeBuffers FAILED: 0x" << std::hex << hr << std::dec
                  << " (requested size: " << width << "x" << height << ")" << std::endl;
        // Re-acquire buffers at the previous (still-valid) size so the renderer
        // keeps usable render targets instead of leaving them all null, which
        // would cause AcquireDrawableTexturePtr to return 0 and trigger an
        // infinite resize-retry loop in the caller.
        D3D12_CPU_DESCRIPTOR_HANDLE rtvHandle = rtvHeap_->GetCPUDescriptorHandleForHeapStart();
        for (UINT n = 0; n < FrameCount; n++) {
            if (SUCCEEDED(swapChain_->GetBuffer(n, IID_PPV_ARGS(&renderTargets_[n])))) {
                device_->CreateRenderTargetView(renderTargets_[n].Get(), nullptr, rtvHandle);
            }
            rtvHandle.ptr += rtvDescriptorSize_;
        }
        frameIndex_ = swapChain_->GetCurrentBackBufferIndex();
        
        // Return false to signal the failure. The caller will retry on the next frame.
        // If we didn't communicate this failure, the host would incorrectly clear its
        // resizePending flag and remain permanently stuck at the old SwapChain resolution,
        // causing Windows DWM to severely stretch the rendered frame across the new window size.
        return false;
    }

    frameIndex_ = swapChain_->GetCurrentBackBufferIndex();

    D3D12_CPU_DESCRIPTOR_HANDLE rtvHandle = rtvHeap_->GetCPUDescriptorHandleForHeapStart();
    for (UINT n = 0; n < FrameCount; n++) {
        if (FAILED(swapChain_->GetBuffer(n, IID_PPV_ARGS(&renderTargets_[n])))) {
            return false;
        }
        device_->CreateRenderTargetView(renderTargets_[n].Get(), nullptr, rtvHandle);
        rtvHandle.ptr += rtvDescriptorSize_;
    }

    width_ = width;
    height_ = height;
    return true;
}

void D3D12Renderer::Shutdown() {
    WaitForGpu();

    if (fenceEvent_) {
        CloseHandle(fenceEvent_);
        fenceEvent_ = nullptr;
    }

    fence_.Reset();
    for (UINT n = 0; n < FrameCount; n++) {
        renderTargets_[n].Reset();
    }
    rtvHeap_.Reset();
    swapChain_.Reset();
    commandQueue_.Reset();
    device_.Reset();
    adapter_.Reset();
}

int64_t D3D12Renderer::AcquireDrawableTexturePtr() {
    return (int64_t)renderTargets_[frameIndex_].Get();
}

void D3D12Renderer::Present(UINT syncInterval) {
    // Present the frame.
    if (FAILED(swapChain_->Present(syncInterval, 0))) {
        // Handle device loss if needed
    }
    MoveToNextFrame();
}

void D3D12Renderer::MoveToNextFrame() {
    // Schedule a Signal command in the queue.
    const UINT64 fenceToSignal = currentFenceValue_;
    if (FAILED(commandQueue_->Signal(fence_.Get(), fenceToSignal))) {
        return;
    }
    currentFenceValue_++;

    // Update the frame index.
    frameIndex_ = swapChain_->GetCurrentBackBufferIndex();

    // If the next frame is not ready to be rendered yet, wait for it.
    if (fence_->GetCompletedValue() < fenceValues_[frameIndex_]) {
        if (FAILED(fence_->SetEventOnCompletion(fenceValues_[frameIndex_], fenceEvent_))) {
            return;
        }
        WaitForSingleObjectEx(fenceEvent_, INFINITE, FALSE);
    }

    // Set the fence value for the next frame.
    fenceValues_[frameIndex_] = currentFenceValue_;
}

void D3D12Renderer::WaitForGpu() {
    if (!commandQueue_ || !fence_) return;

    // Schedule a Signal command in the queue.
    if (FAILED(commandQueue_->Signal(fence_.Get(), currentFenceValue_))) {
        return;
    }

    // Wait until the fence has been processed.
    if (FAILED(fence_->SetEventOnCompletion(currentFenceValue_, fenceEvent_))) {
        return;
    }
    WaitForSingleObjectEx(fenceEvent_, INFINITE, FALSE);

    // Increment the fence value.
    currentFenceValue_++;

    // All frames are done, update their expected fence values
    for (UINT n = 0; n < FrameCount; n++) {
        fenceValues_[n] = currentFenceValue_;
    }
}
