#ifndef TERMUX_TERMINAL_PROGRESS_H
#define TERMUX_TERMINAL_PROGRESS_H

#include <ghostty/vt/terminal.h>

#include <array>
#include <cstddef>

namespace termux {

struct TerminalProgressReport {
    GhosttyTerminalProgressState state = GHOSTTY_TERMINAL_PROGRESS_STATE_REMOVE;
    int value = -1;

    bool indeterminate() const {
        return state == GHOSTTY_TERMINAL_PROGRESS_STATE_INDETERMINATE ||
               ((state == GHOSTTY_TERMINAL_PROGRESS_STATE_SET ||
                 state == GHOSTTY_TERMINAL_PROGRESS_STATE_ERROR) && value < 0);
    }
};

struct TerminalProgressDispatch {
    std::array<TerminalProgressReport, 2> reports{};
    size_t count = 0;
};

// A PTY read may contain many OSC 9;4 reports. Android keeps its last
// percentage while indeterminate or paused without a value, but REMOVE
// clears it. Keep the final state and its last numerical update so merging
// a read cannot lose a percentage needed by a later pause. No state crosses
// feed boundaries: the Java host owns visibility and session-switch resets.
class TerminalProgressBatch {
public:
    void clear() { *this = TerminalProgressBatch{}; }

    void add(GhosttyTerminalProgressState state, int value) {
        pending_ = true;
        latest_ = {state, value};
        if (state == GHOSTTY_TERMINAL_PROGRESS_STATE_REMOVE) {
            value_update_ = {state, -1};
            have_value_update_ = true;
        } else if (!latest_.indeterminate() && value >= 0) {
            value_update_ = latest_;
            have_value_update_ = true;
        }
    }

    TerminalProgressDispatch finish() const {
        TerminalProgressDispatch result;
        if (!pending_) return result;

        TerminalProgressReport final = latest_;
        if (final.state == GHOSTTY_TERMINAL_PROGRESS_STATE_PAUSE &&
            final.value < 0 && have_value_update_) {
            final.value = value_update_.state == GHOSTTY_TERMINAL_PROGRESS_STATE_REMOVE
                ? 0 : value_update_.value;
        }
        // An indeterminate report does not update ProgressBar's backing
        // percentage. Deliver that update first, even if the pause that
        // needs it will arrive in another PTY read.
        if (final.indeterminate() && have_value_update_)
            result.reports[result.count++] = value_update_;
        result.reports[result.count++] = final;
        return result;
    }

private:
    bool pending_ = false;
    bool have_value_update_ = false;
    TerminalProgressReport latest_{};
    TerminalProgressReport value_update_{};
};

} // namespace termux

#endif
