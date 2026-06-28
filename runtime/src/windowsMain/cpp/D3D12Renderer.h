#pragma once
#include <windows.h>
#include <d3d12.h>
#include <dxgi1_4.h>
#include <wrl.h>
#include <stdint.h>

class D3D12Renderer {
public:
    D3D12Renderer();
    ~D3D12Renderer();

    bool Initialize(HWND hwnd, int width, int height);
    bool Resize(int width, int height);
    void Shutdown();

    IDXGIAdapter1* GetAdapter() const { return adapter_.Get(); }
    ID3D12Device* GetDevice() const { return device_.Get(); }
    ID3D12CommandQueue* GetCommandQueue() const { return commandQueue_.Get(); }

    // Acquires the current back-buffer resource pointer (to be passed to Skia as textureResourcePtr)
    int64_t AcquireDrawableTexturePtr();
    // Presents the current frame. syncInterval=0 for immediate (no VSync), 1 for VSync.
    void Present(UINT syncInterval = 1);

private:
    static const UINT FrameCount = 3;

    Microsoft::WRL::ComPtr<IDXGIAdapter1> adapter_;
    Microsoft::WRL::ComPtr<ID3D12Device> device_;
    Microsoft::WRL::ComPtr<ID3D12CommandQueue> commandQueue_;
    Microsoft::WRL::ComPtr<IDXGISwapChain3> swapChain_;
    Microsoft::WRL::ComPtr<ID3D12Resource> renderTargets_[FrameCount];
    Microsoft::WRL::ComPtr<ID3D12DescriptorHeap> rtvHeap_;
    UINT rtvDescriptorSize_ = 0;
    UINT frameIndex_ = 0;
    int width_ = 0;
    int height_ = 0;

    // Synchronization objects
    HANDLE fenceEvent_ = nullptr;
    Microsoft::WRL::ComPtr<ID3D12Fence> fence_;
    UINT64 fenceValues_[FrameCount] = {0};
    UINT64 currentFenceValue_ = 0;

    void MoveToNextFrame();
    void WaitForGpu();
};
