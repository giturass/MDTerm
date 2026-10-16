// Adapted from XYenon/termux-app-ghostty, commit
// 9bdfe0b90bbe3e21cd6070e488d9d38abe3af3e9 (GPL-3.0-only).
// https://github.com/XYenon/termux-app-ghostty
#ifndef TERMUX_GHOSTTY_ENGINE_H
#define TERMUX_GHOSTTY_ENGINE_H

#include <jni.h>
#include <pthread.h>
#include <stdint.h>

#include <chrono>
#include <string>
#include <vector>

#include <ghostty/vt.h>

#include "terminal_progress.h"

struct TermuxGhosttyEngine {
    JavaVM *vm;
    jobject output;
    jmethodID write_method;
    jmethodID title_method;
    jmethodID pwd_method;
    jmethodID bookmark_location_method;
    jmethodID mouse_shape_method;
    jmethodID desktop_notification_method;
    jmethodID progress_report_method;
    jmethodID program_status_method;
    jmethodID program_status_prompt_method;
    jmethodID clipboard_write_method;
    jmethodID clipboard_permission_method;
    jmethodID clipboard_mimes_method;
    jmethodID clipboard_read_method;
    jmethodID clipboard_read_complete_method;
    jmethodID bell_method;
    jmethodID colors_method;
    pthread_mutex_t mutex;
    GhosttyTerminal terminal;
    GhosttyKeyEncoder key_encoder;
    GhosttyKeyEvent key_event;
    GhosttyMouseEncoder mouse_encoder;
    GhosttyMouseEvent mouse_event;
    GhosttyRenderState render_state;
    bool render_held;
    std::chrono::steady_clock::time_point render_hold_started;
    GhosttyRenderStateRowIterator row_iterator;
    GhosttyRenderStateRowCells row_cells;
    GhosttySearch search;

    // Last title/pwd published to Java. The change callbacks only mark them
    // dirty; nativeFeed reads and compares the terminal values on demand.
    std::string title;
    std::string pwd;
    bool title_dirty;
    bool pwd_dirty;
    // Reused VT input copy for nativeFeed; only touched under the mutex.
    std::vector<uint8_t> feed_buffer;
    int mouse_shape;
    bool pending_desktop_notification;
    std::string pending_notification_title;
    std::string pending_notification_body;
    bool callback_allocation_failed;
    bool pending_pty_writes_overflow;
    size_t clipboard_read_bytes_remaining;
    size_t clipboard_read_requests_remaining;
    termux::TerminalProgressBatch pending_progress;
    struct ProgramStatusEvent {
        bool prompt = false;
        int state = GHOSTTY_PROGRAM_STATUS_STATE_CLEAR;
        int kind = GHOSTTY_PROGRAM_STATUS_KIND_NONE;
        int progress = -1;
        std::string id, app, title, message;
    };
    // Preserve report/clear/prompt ordering within a PTY feed. Strings are
    // copied while borrowed upstream data is valid, then delivered unlocked.
    std::vector<ProgramStatusEvent> *pending_program_status = nullptr;
    // Borrowed OSC payloads are copied during feed, then published without
    // the native mutex so bookmark callbacks can safely access the session.
    std::vector<std::string> *pending_bookmark_locations = nullptr;
    struct TermuxVulkanRenderer *renderer;

    // When non-null, PTY responses produced reentrantly from a
    // ghostty_terminal_* call are appended here instead of being written to
    // Java, so the caller can flush them after releasing the engine mutex.
    std::vector<uint8_t> *pending_pty_writes;
};

TermuxGhosttyEngine *termux_ghostty_engine_from_handle(jlong handle);
void termux_ghostty_engine_lock(TermuxGhosttyEngine *engine);
void termux_ghostty_engine_unlock(TermuxGhosttyEngine *engine);
void termux_ghostty_engine_write(TermuxGhosttyEngine *engine,
                                 const uint8_t *data, size_t length);

#endif
