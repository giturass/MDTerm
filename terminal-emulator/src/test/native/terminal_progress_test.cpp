#include "terminal_progress.h"

#include <algorithm>
#include <array>
#include <initializer_list>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

namespace {

// These assertions remain active in the CI Release build.
#define CHECK(condition)                                                       \
    do {                                                                       \
        if (!(condition))                                                      \
            throw std::runtime_error(std::string(__FILE__) + ":" +             \
                                     std::to_string(__LINE__) +                \
                                     ": " #condition);                         \
    } while (false)

using Report = termux::TerminalProgressReport;
using Batch = termux::TerminalProgressBatch;
constexpr auto remove = GHOSTTY_TERMINAL_PROGRESS_STATE_REMOVE;
constexpr auto set = GHOSTTY_TERMINAL_PROGRESS_STATE_SET;
constexpr auto error = GHOSTTY_TERMINAL_PROGRESS_STATE_ERROR;
constexpr auto indeterminate = GHOSTTY_TERMINAL_PROGRESS_STATE_INDETERMINATE;
constexpr auto pause = GHOSTTY_TERMINAL_PROGRESS_STATE_PAUSE;

void check_dispatch(std::initializer_list<Report> input,
                    std::initializer_list<Report> expected) {
    Batch batch;
    for (const auto &report : input) batch.add(report.state, report.value);
    const auto result = batch.finish();
    CHECK(result.count == expected.size());
    size_t index = 0;
    for (const auto &report : expected) {
        CHECK(result.reports[index].state == report.state);
        CHECK(result.reports[index++].value == report.value);
    }
}

void coalesced_callback_fixtures() {
    // Already-parsed OSC 9;4 callbacks; Ghostty's C API suite tests parsing.
    check_dispatch({}, {});
    check_dispatch({{pause, -1}}, {{pause, -1}});
    check_dispatch({{set, 60}, {pause, -1}}, {{pause, 60}});
    check_dispatch({{set, 60}, {error, 25}, {pause, -1}}, {{pause, 25}});
    check_dispatch({{set, 60}, {indeterminate, -1}, {pause, -1}}, {{pause, 60}});
    check_dispatch({{set, 60}, {indeterminate, -1}}, {{set, 60}, {indeterminate, -1}});
    check_dispatch({{set, 60}, {error, -1}}, {{set, 60}, {error, -1}});
    check_dispatch({{set, 60}, {remove, -1}}, {{remove, -1}});
    // REMOVE also represents the callback emitted by RIS.
    check_dispatch({{set, 60}, {remove, -1}, {pause, -1}}, {{pause, 0}});
    check_dispatch({{set, 60}, {remove, -1}, {indeterminate, -1}},
                   {{remove, -1}, {indeterminate, -1}});
}

// The Android consumer's observable state, including the stored percentage
// that ProgressBar keeps while indeterminate. Robolectric covers the actual
// widget transitions in TerminalOscUiTest; this oracle compares every way of
// splitting the callback stream into PTY reads.
struct UiProgress {
    bool visible = false;
    bool busy = false;
    int percentage = 0;
    GhosttyTerminalProgressState state = remove;

    void apply(const Report &report) {
        state = report.state;
        switch (state) {
            case GHOSTTY_TERMINAL_PROGRESS_STATE_REMOVE:
                visible = false;
                busy = false;
                percentage = 0;
                return;
            case GHOSTTY_TERMINAL_PROGRESS_STATE_SET:
            case GHOSTTY_TERMINAL_PROGRESS_STATE_ERROR:
                busy = report.value < 0;
                break;
            case GHOSTTY_TERMINAL_PROGRESS_STATE_INDETERMINATE:
                busy = true;
                break;
            case GHOSTTY_TERMINAL_PROGRESS_STATE_PAUSE:
                busy = false;
                break;
            default:
                throw std::runtime_error("Unexpected progress state in fixture");
        }
        visible = true;
        if (!busy && report.value >= 0) percentage = std::clamp(report.value, 0, 100);
    }

    bool operator==(const UiProgress &other) const {
        return visible == other.visible && busy == other.busy &&
               percentage == other.percentage && state == other.state;
    }
};

void check_all_partitions(const std::vector<Report> &stream, UiProgress initial) {
    CHECK(!stream.empty() && stream.size() < sizeof(size_t) * 8);
    const size_t combinations = size_t{1} << (stream.size() - 1);
    for (size_t boundaries = 0; boundaries < combinations; ++boundaries) {
        UiProgress reference = initial;
        UiProgress coalesced = initial;
        Batch batch;
        for (size_t i = 0; i < stream.size(); ++i) {
            reference.apply(stream[i]);
            batch.add(stream[i].state, stream[i].value);
            if (i + 1 != stream.size() && (boundaries & (size_t{1} << i)) == 0)
                continue;
            const auto result = batch.finish();
            CHECK(result.count > 0 && result.count <= 2);
            for (size_t j = 0; j < result.count; ++j) coalesced.apply(result.reports[j]);
            CHECK(coalesced == reference);
            batch.clear();
            CHECK(batch.finish().count == 0);
        }
    }
}

void feed_boundaries_do_not_change_progress() {
    UiProgress paused;
    paused.apply({set, 37});
    paused.apply({pause, -1});
    UiProgress busy;
    busy.apply({set, 85});
    busy.apply({indeterminate, -1});
    const std::array<UiProgress, 3> initial_states{UiProgress{}, paused, busy};
    const std::vector<std::vector<Report>> streams{
        {{set, 60}, {pause, -1}},
        {{set, 60}, {indeterminate, -1}, {pause, -1}},
        {{set, 60}, {set, -1}, {pause, -1}},
        {{set, 60}, {error, -1}, {pause, -1}},
        {{set, 60}, {remove, -1}, {pause, -1}},
        {{set, 60}, {remove, -1}, {indeterminate, -1}, {pause, -1}},
        {{set, 60}, {indeterminate, -1}, {remove, -1}, {pause, -1}},
        {{set, 60}, {remove, -1}, {set, 20}, {pause, -1}},
        {{set, 60}, {error, 25}, {pause, -1}},
        {{set, 60}, {pause, 0}, {indeterminate, -1}, {pause, -1}},
        {{pause, -1}, {indeterminate, -1}, {pause, -1}},
        // State 3 never sets the widget's backing percentage.
        {{set, 60}, {indeterminate, 90}, {pause, -1}},
    };
    for (const auto &initial : initial_states)
        for (const auto &stream : streams) check_all_partitions(stream, initial);
}

void batches_are_bounded_and_do_not_retain_old_percentages() {
    Batch batch;
    for (int i = 0; i < 10000; ++i) batch.add(set, i % 101);
    batch.add(set, 88);
    batch.add(indeterminate, -1);
    auto result = batch.finish();
    CHECK(result.count == 2);
    CHECK(result.reports[0].value == 88);
    CHECK(result.reports[1].state == indeterminate);
    batch.clear();
    batch.add(pause, -1);
    result = batch.finish();
    CHECK(result.count == 1);
    CHECK(result.reports[0].state == pause && result.reports[0].value == -1);
}

} // namespace

int main() {
    try {
        coalesced_callback_fixtures();
        feed_boundaries_do_not_change_progress();
        batches_are_bounded_and_do_not_retain_old_percentages();
        std::cout << "Terminal progress coalescing checks passed\n";
        return 0;
    } catch (const std::exception &e) {
        std::cerr << e.what() << '\n';
        return 1;
    }
}
