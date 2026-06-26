#pragma once
#include <oleidl.h>
#include <shellapi.h>
#include "ComposeNativeHost.h"
#include "HostJvm.h"

class ComposeDropTarget : public IDropTarget {
public:
    ComposeDropTarget(HWND hwnd, int64_t runtimeId);
    virtual ~ComposeDropTarget();

    STDMETHODIMP QueryInterface(REFIID riid, void** ppv) override;
    STDMETHODIMP_(ULONG) AddRef() override;
    STDMETHODIMP_(ULONG) Release() override;

    STDMETHODIMP DragEnter(IDataObject* pDataObj, DWORD grfKeyState, POINTL pt, DWORD* pdwEffect) override;
    STDMETHODIMP DragOver(DWORD grfKeyState, POINTL pt, DWORD* pdwEffect) override;
    STDMETHODIMP DragLeave() override;
    STDMETHODIMP Drop(IDataObject* pDataObj, DWORD grfKeyState, POINTL pt, DWORD* pdwEffect) override;

private:
    HWND hwnd_;
    int64_t runtimeId_;
    ULONG refCount_;
    DragDropPayload payload_;

    DWORD SelectEffect(DWORD grfKeyState, DWORD allowedEffects);
    int MapEffectToAction(DWORD effect);
};
