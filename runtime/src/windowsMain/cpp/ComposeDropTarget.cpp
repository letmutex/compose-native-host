#include "ComposeDropTarget.h"

#pragma comment(lib, "ole32.lib")
#pragma comment(lib, "shell32.lib")

static DragDropPayload ExtractDragDropPayload(IDataObject* pDataObj) {
    DragDropPayload payload;

    FORMATETC fmteDrop = { CF_HDROP, NULL, DVASPECT_CONTENT, -1, TYMED_HGLOBAL };
    STGMEDIUM mediumDrop;
    if (SUCCEEDED(pDataObj->GetData(&fmteDrop, &mediumDrop))) {
        HDROP hDrop = (HDROP)GlobalLock(mediumDrop.hGlobal);
        if (hDrop) {
            UINT fileCount = DragQueryFileW(hDrop, 0xFFFFFFFF, NULL, 0);
            for (UINT i = 0; i < fileCount; ++i) {
                wchar_t szPath[MAX_PATH];
                if (DragQueryFileW(hDrop, i, szPath, MAX_PATH) > 0) {
                    payload.files.push_back(szPath);
                }
            }
            GlobalUnlock(mediumDrop.hGlobal);
            if (!payload.files.empty()) {
                payload.payloadKind = 1;
            }
        }
        ReleaseStgMedium(&mediumDrop);
    }

    if (payload.payloadKind == 0) {
        FORMATETC fmteText = { CF_UNICODETEXT, NULL, DVASPECT_CONTENT, -1, TYMED_HGLOBAL };
        STGMEDIUM mediumText;
        if (SUCCEEDED(pDataObj->GetData(&fmteText, &mediumText))) {
            wchar_t* pData = (wchar_t*)GlobalLock(mediumText.hGlobal);
            if (pData) {
                payload.text = pData;
                payload.payloadKind = 2;
                GlobalUnlock(mediumText.hGlobal);
            }
            ReleaseStgMedium(&mediumText);
        }
    }

    return payload;
}

ComposeDropTarget::ComposeDropTarget(HWND hwnd, int64_t runtimeId)
    : hwnd_(hwnd), runtimeId_(runtimeId), refCount_(1) {}

ComposeDropTarget::~ComposeDropTarget() {}

STDMETHODIMP ComposeDropTarget::QueryInterface(REFIID riid, void** ppv) {
    if (riid == IID_IUnknown || riid == IID_IDropTarget) {
        *ppv = static_cast<IDropTarget*>(this);
        AddRef();
        return S_OK;
    }
    *ppv = nullptr;
    return E_NOINTERFACE;
}

STDMETHODIMP_(ULONG) ComposeDropTarget::AddRef() {
    return InterlockedIncrement(&refCount_);
}

STDMETHODIMP_(ULONG) ComposeDropTarget::Release() {
    ULONG count = InterlockedDecrement(&refCount_);
    if (count == 0) {
        delete this;
    }
    return count;
}

STDMETHODIMP ComposeDropTarget::DragEnter(IDataObject* pDataObj, DWORD grfKeyState, POINTL pt, DWORD* pdwEffect) {
    payload_ = ExtractDragDropPayload(pDataObj);
    if (payload_.payloadKind == 0) {
        *pdwEffect = DROPEFFECT_NONE;
        return S_OK;
    }

    POINT clientPt = { pt.x, pt.y };
    ScreenToClient(hwnd_, &clientPt);

    DWORD effect = SelectEffect(grfKeyState, *pdwEffect);
    int action = MapEffectToAction(effect);

    bool accepted = HostJvm::Get().DragEnter(
        runtimeId_,
        clientPt.x,
        clientPt.y,
        action,
        payload_,
        GetTickCount64()
    );

    *pdwEffect = accepted ? effect : DROPEFFECT_NONE;
    return S_OK;
}

STDMETHODIMP ComposeDropTarget::DragOver(DWORD grfKeyState, POINTL pt, DWORD* pdwEffect) {
    if (payload_.payloadKind == 0) {
        *pdwEffect = DROPEFFECT_NONE;
        return S_OK;
    }

    POINT clientPt = { pt.x, pt.y };
    ScreenToClient(hwnd_, &clientPt);

    DWORD effect = SelectEffect(grfKeyState, *pdwEffect);
    int action = MapEffectToAction(effect);

    bool accepted = HostJvm::Get().DragOver(
        runtimeId_,
        clientPt.x,
        clientPt.y,
        action,
        payload_,
        GetTickCount64()
    );

    *pdwEffect = accepted ? effect : DROPEFFECT_NONE;
    return S_OK;
}

STDMETHODIMP ComposeDropTarget::DragLeave() {
    HostJvm::Get().DragLeave(runtimeId_);
    HostJvm::Get().DragEnded(runtimeId_);
    payload_ = DragDropPayload();
    return S_OK;
}

STDMETHODIMP ComposeDropTarget::Drop(IDataObject* pDataObj, DWORD grfKeyState, POINTL pt, DWORD* pdwEffect) {
    if (payload_.payloadKind == 0) {
        *pdwEffect = DROPEFFECT_NONE;
        return S_OK;
    }

    POINT clientPt = { pt.x, pt.y };
    ScreenToClient(hwnd_, &clientPt);

    DWORD effect = SelectEffect(grfKeyState, *pdwEffect);
    int action = MapEffectToAction(effect);

    bool accepted = HostJvm::Get().Drop(
        runtimeId_,
        clientPt.x,
        clientPt.y,
        action,
        payload_,
        GetTickCount64()
    );

    HostJvm::Get().DragEnded(runtimeId_);

    *pdwEffect = accepted ? effect : DROPEFFECT_NONE;
    payload_ = DragDropPayload();
    return S_OK;
}

DWORD ComposeDropTarget::SelectEffect(DWORD grfKeyState, DWORD allowedEffects) {
    DWORD preferred = DROPEFFECT_COPY;
    if (grfKeyState & MK_SHIFT) {
        preferred = DROPEFFECT_MOVE;
    } else if (grfKeyState & MK_CONTROL) {
        preferred = DROPEFFECT_COPY;
    }

    if (preferred & allowedEffects) {
        return preferred;
    }

    if (allowedEffects & DROPEFFECT_COPY) return DROPEFFECT_COPY;
    if (allowedEffects & DROPEFFECT_MOVE) return DROPEFFECT_MOVE;
    if (allowedEffects & DROPEFFECT_LINK) return DROPEFFECT_LINK;

    return DROPEFFECT_NONE;
}

int ComposeDropTarget::MapEffectToAction(DWORD effect) {
    if (effect & DROPEFFECT_COPY) return 1;
    if (effect & DROPEFFECT_MOVE) return 2;
    if (effect & DROPEFFECT_LINK) return 3;
    return 1;
}
