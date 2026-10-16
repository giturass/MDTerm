// Adapted from XYenon/termux-app-ghostty, commit
// e017c50b88f413b6e7e61b2d55d625b259859235 (GPL-3.0-only).
// https://github.com/XYenon/termux-app-ghostty
package com.termux.app.terminal;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.logger.Logger;
import com.termux.terminal.GhosttyTerminal;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

/** A find bar bound to the terminal that was visible when it opened. */
final class TerminalSearchDialog {
    private final TermuxActivity activity;
    private AlertDialog dialog;
    private TerminalSession session;
    private GhosttyTerminal terminal;

    TerminalSearchDialog(TermuxActivity activity) {
        this.activity = activity;
    }

    void show() {
        dismissIfSessionChanged();
        if (dialog != null || activity.isFinishing() || activity.isDestroyed()) return;
        TerminalSession currentSession = activity.getCurrentSession();
        GhosttyTerminal currentTerminal = currentSession == null ? null : currentSession.getTerminal();
        if (currentTerminal == null || currentTerminal.isClosed()) return;
        session = currentSession;
        terminal = currentTerminal;

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity)
            .setBackgroundInsetStart(dp(12)).setBackgroundInsetEnd(dp(12))
            .setBackgroundInsetTop(0).setBackgroundInsetBottom(dp(12));
        View content = LayoutInflater.from(builder.getContext())
            .inflate(R.layout.dialog_terminal_search, null);
        EditText input = content.findViewById(R.id.terminal_search_input);
        TextView status = content.findViewById(R.id.terminal_search_status);
        View previous = content.findViewById(R.id.terminal_search_previous);
        View next = content.findViewById(R.id.terminal_search_next);
        AlertDialog created = builder.setView(content).create();
        dialog = created;
        TerminalView terminalView = activity.getTerminalView();
        View toolbar = activity.getTerminalToolbar();
        int toolbarVisibility = toolbar == null ? View.GONE : toolbar.getVisibility();

        // Ghostty SELECT_NEXT walks upward into older history; SELECT_PREV walks down.
        previous.setOnClickListener(ignored -> search(content, true));
        next.setOnClickListener(ignored -> search(content, false));
        content.findViewById(R.id.terminal_search_close).setOnClickListener(ignored -> dismiss());
        created.setOnDismissListener(ignored -> {
            if (dialog == created) {
                dialog = null;
                session = null;
                terminal = null;
            }
            if (toolbar != null) {
                // A session can close or navigation can open before this dismissal callback runs.
                boolean navigationOpen = activity.isWaitingForSession() ||
                    (activity.getDrawer() != null && activity.getDrawer().isDrawerVisible(Gravity.START));
                toolbar.setVisibility(navigationOpen ? View.GONE : toolbarVisibility);
            }
            currentTerminal.clearSearch();
            if (terminalView.getTerminal() == currentTerminal) {
                terminalView.setSearchActive(false);
                terminalView.onScreenUpdated(true);
            }
        });
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable text) {
                if (!isCurrentTerminal()) {
                    dismiss();
                    return;
                }
                currentTerminal.clearSearch();
                status.setText(text.length() == 0 ? null : activity.getString(R.string.terminal_search_submit));
                previous.setEnabled(text.length() > 0);
                next.setEnabled(text.length() > 0);
                terminalView.onScreenUpdated(true);
            }
        });
        // TextView only sends unmodified Enter to editor actions and otherwise
        // advances focus on key-up. Own both phases so repeated searches stay in
        // this field and Shift+Enter can move in the opposite direction.
        input.setOnKeyListener((view, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_ENTER && keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER)
                return false;
            if (event.getAction() == KeyEvent.ACTION_DOWN)
                search(content, !event.isShiftPressed());
            return true;
        });
        input.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return false;
            search(content, true);
            return true;
        });
        input.requestFocus();
        terminalView.setSearchActive(true);
        if (toolbar != null) toolbar.setVisibility(View.GONE);
        created.show();
        Window window = created.getWindow();
        if (window != null) {
            // Keep matched terminal text visible while the field is above the IME.
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
            window.setGravity(Gravity.BOTTOM);
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    private void search(View content, boolean older) {
        if (!isCurrentTerminal()) {
            dismiss();
            return;
        }
        EditText input = content.findViewById(R.id.terminal_search_input);
        String query = input.getText().toString();
        if (query.isEmpty()) return;
        TextView status = content.findViewById(R.id.terminal_search_status);
        try {
            int[] result = terminal.search(query, older);
            boolean found = result != null && result.length >= 2 && result[1] > 0;
            content.findViewById(R.id.terminal_search_previous).setEnabled(found);
            content.findViewById(R.id.terminal_search_next).setEnabled(found);
            status.setText(found ? activity.getString(R.string.terminal_search_results_count,
                result[0], result[1]) : activity.getString(R.string.terminal_search_no_matches));
        } catch (RuntimeException e) {
            Logger.logError("TerminalSearch", "Terminal search failed: " + e);
            status.setText(R.string.terminal_search_failed);
        }
        activity.getTerminalView().onScreenUpdated(true);
    }

    void dismissIfSessionChanged() {
        if (dialog != null && !isCurrentTerminal()) dismiss();
    }

    void dismiss() {
        if (dialog != null) dialog.dismiss();
    }

    private boolean isCurrentTerminal() {
        return !activity.isFinishing() && !activity.isDestroyed()
            && session != null && activity.getCurrentSession() == session
            && session.getTerminal() == terminal && terminal != null && !terminal.isClosed();
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
