#include "InputEvents.h"
#include <string.h>
#include <stdlib.h>

InputEventStore::InputEventStore() {}

InputEventStore::~InputEventStore() {}

void InputEventStore::EnqueuePointer(const PointerEventRecord& record) {
    std::lock_guard<std::mutex> lock(lock_);
    InputEventRecord event;
    event.kind = INPUT_EVENT_KIND_POINTER;
    event.pointer = record;
    if (!CoalesceLastQueuedEvent(event)) {
        queue_.push_back(event);
    }
}

void InputEventStore::EnqueueKey(const KeyEventRecord& record) {
    std::lock_guard<std::mutex> lock(lock_);
    InputEventRecord event;
    event.kind = INPUT_EVENT_KIND_KEY;
    event.key = record;
    queue_.push_back(event);
}

void InputEventStore::EnqueueText(const TextEventRecord& record) {
    std::lock_guard<std::mutex> lock(lock_);
    InputEventRecord event;
    event.kind = INPUT_EVENT_KIND_TEXT;
    // Activate the `key` union member (it shares the eventType/timestampMillis
    // layout we reuse for text events) before writing through it to avoid
    // accessing a non-active union member.
    event.key = KeyEventRecord{};
    event.key.eventType = record.eventType;
    event.key.timestampMillis = record.timestampMillis;
    event.text = record.text;
    queue_.push_back(event);
}

int32_t InputEventStore::PollBatch(
    int32_t maxCount,
    int64_t* records,
    char** texts
) {
    std::lock_guard<std::mutex> lock(lock_);
    if (maxCount <= 0 || head_ >= queue_.size()) {
        return 0;
    }

    int32_t count = 0;
    const int32_t stride = 10;

    while (count < maxCount && head_ < queue_.size()) {
        int64_t* record = records + (count * stride);
        memset(record, 0, stride * sizeof(int64_t));
        if (texts) {
            texts[count] = nullptr;
        }

        const auto& event = queue_[head_];
        switch (event.kind) {
            case INPUT_EVENT_KIND_POINTER: {
                record[0] = INPUT_EVENT_KIND_POINTER;
                record[1] = event.pointer.timestampMillis;
                record[2] = event.pointer.eventType;

                int32_t xBits, yBits, sxBits, syBits;
                memcpy(&xBits, &event.pointer.x, sizeof(float));
                memcpy(&yBits, &event.pointer.y, sizeof(float));
                memcpy(&sxBits, &event.pointer.scrollX, sizeof(float));
                memcpy(&syBits, &event.pointer.scrollY, sizeof(float));

                record[3] = xBits;
                record[4] = yBits;
                record[5] = sxBits;
                record[6] = syBits;
                record[7] = event.pointer.buttonsMask;
                record[8] = event.pointer.modifiersMask;
                record[9] = event.pointer.buttonIndex;
                break;
            }
            case INPUT_EVENT_KIND_KEY: {
                record[0] = INPUT_EVENT_KIND_KEY;
                record[1] = event.key.timestampMillis;
                record[2] = event.key.eventType;
                record[3] = event.key.keyCode;
                record[4] = event.key.codePoint;
                record[5] = event.key.modifiersMask;
                break;
            }
            case INPUT_EVENT_KIND_TEXT: {
                record[0] = INPUT_EVENT_KIND_TEXT;
                record[1] = event.key.timestampMillis;
                record[2] = event.key.eventType;
                if (texts) {
                    texts[count] = _strdup(event.text.c_str());
                }
                break;
            }
            default:
                break;
        }

        head_++;
        count++;
    }

    CompactIfNeeded();
    return count;
}

bool InputEventStore::CoalesceLastQueuedEvent(const InputEventRecord& event) {
    if (head_ >= queue_.size()) {
        return false;
    }
    size_t lastIndex = queue_.size() - 1;
    const auto& previous = queue_[lastIndex];
    if (previous.kind == INPUT_EVENT_KIND_POINTER && event.kind == INPUT_EVENT_KIND_POINTER) {
        if (previous.pointer.eventType != event.pointer.eventType) {
            return false;
        }
        if (event.pointer.eventType == pointerEventTypeMove) {
            if (previous.pointer.buttonsMask == event.pointer.buttonsMask &&
                previous.pointer.modifiersMask == event.pointer.modifiersMask &&
                previous.pointer.buttonIndex == event.pointer.buttonIndex) {
                queue_[lastIndex] = event;
                return true;
            }
        } else if (event.pointer.eventType == pointerEventTypeScroll) {
            if (previous.pointer.buttonsMask == event.pointer.buttonsMask &&
                previous.pointer.modifiersMask == event.pointer.modifiersMask) {
                queue_[lastIndex].pointer.scrollX += event.pointer.scrollX;
                queue_[lastIndex].pointer.scrollY += event.pointer.scrollY;
                queue_[lastIndex].pointer.timestampMillis = event.pointer.timestampMillis;
                queue_[lastIndex].pointer.x = event.pointer.x;
                queue_[lastIndex].pointer.y = event.pointer.y;
                return true;
            }
        }
    }
    return false;
}

void InputEventStore::CompactIfNeeded() {
    const size_t compactThreshold = 64;
    if (head_ >= compactThreshold && head_ * 2 >= queue_.size()) {
        queue_.erase(queue_.begin(), queue_.begin() + head_);
        head_ = 0;
    }
}
