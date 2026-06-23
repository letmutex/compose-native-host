#pragma once
#include <stdint.h>
#include <string>
#include <vector>
#include <mutex>

enum InputEventKind {
    INPUT_EVENT_KIND_NONE = 0,
    INPUT_EVENT_KIND_POINTER = 1,
    INPUT_EVENT_KIND_KEY = 2,
    INPUT_EVENT_KIND_TEXT = 3
};

constexpr int32_t pointerEventTypePress = 1;
constexpr int32_t pointerEventTypeRelease = 2;
constexpr int32_t pointerEventTypeMove = 3;
constexpr int32_t pointerEventTypeEnter = 4;
constexpr int32_t pointerEventTypeExit = 5;
constexpr int32_t pointerEventTypeScroll = 6;

constexpr int32_t keyEventTypeDown = 1;
constexpr int32_t keyEventTypeUp = 2;

constexpr int32_t textInputEventTypeCommit = 1;
constexpr int32_t textInputEventTypeSetComposing = 2;
constexpr int32_t textInputEventTypeFinishComposing = 3;

constexpr int32_t pointerButtonPrimary = 1 << 0;
constexpr int32_t pointerButtonSecondary = 1 << 1;
constexpr int32_t pointerButtonTertiary = 1 << 2;
constexpr int32_t pointerButtonBack = 1 << 3;
constexpr int32_t pointerButtonForward = 1 << 4;

constexpr int32_t keyboardModifierCtrl = 1 << 0;
constexpr int32_t keyboardModifierMeta = 1 << 1;
constexpr int32_t keyboardModifierAlt = 1 << 2;
constexpr int32_t keyboardModifierShift = 1 << 3;

struct PointerEventRecord {
    int32_t eventType;
    int64_t timestampMillis;
    float x;
    float y;
    float scrollX;
    float scrollY;
    int32_t buttonsMask;
    int32_t modifiersMask;
    int32_t buttonIndex;
};

struct KeyEventRecord {
    int32_t eventType;
    int64_t timestampMillis;
    int32_t keyCode;
    int32_t codePoint;
    int32_t modifiersMask;
};

struct TextEventRecord {
    int32_t eventType;
    int64_t timestampMillis;
    std::string text;
};

struct InputEventRecord {
    InputEventKind kind;
    union {
        PointerEventRecord pointer;
        KeyEventRecord key;
    };
    std::string text;
};

class InputEventStore {
public:
    InputEventStore();
    ~InputEventStore();

    void EnqueuePointer(const PointerEventRecord& record);
    void EnqueueKey(const KeyEventRecord& record);
    void EnqueueText(const TextEventRecord& record);

    int32_t PollBatch(
        int32_t maxCount,
        int64_t* records,
        char** texts
    );

private:
    std::mutex lock_;
    std::vector<InputEventRecord> queue_;
    size_t head_ = 0;

    bool CoalesceLastQueuedEvent(const InputEventRecord& event);
    void CompactIfNeeded();
};
