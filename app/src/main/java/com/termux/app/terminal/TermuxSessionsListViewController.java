package com.termux.app.terminal;

import android.annotation.SuppressLint;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.PopupMenu;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;

import java.util.ArrayList;
import java.util.List;

public class TermuxSessionsListViewController extends ArrayAdapter<TermuxSession> implements AdapterView.OnItemClickListener {

    final TermuxActivity mActivity;
    private final List<TermuxSession> mDisplayedSessions;

    public TermuxSessionsListViewController(TermuxActivity activity, List<TermuxSession> sessionList) {
        super(activity.getApplicationContext(), R.layout.item_terminal_sessions_list, sessionList);
        this.mActivity = activity;
        mDisplayedSessions = new ArrayList<>(sessionList);
    }

    @Override
    public void notifyDataSetChanged() {
        ListView list = mActivity.findViewById(R.id.terminal_sessions_list);
        boolean sameSessions = mDisplayedSessions.size() == getCount();
        for (int i = 0; sameSessions && i < getCount(); i++) {
            sameSessions = mDisplayedSessions.get(i) == getItem(i);
        }
        // A structural notification may still be waiting for ListView to lay out its rows.
        for (int i = 0; sameSessions && i < list.getChildCount(); i++) {
            int position = list.getFirstVisiblePosition() + i;
            sameSessions = position >= 0 && position < getCount()
                && list.getChildAt(i).getTag() == getItem(position).getTerminalSession();
        }
        if (sameSessions && list.getAdapter() == this) {
            // A full ListView rebind temporarily detaches each row and turns an in-flight
            // button ACTION_UP into ACTION_CANCEL. Titles and selection only need rebinding.
            for (int i = 0; i < list.getChildCount(); i++) {
                getView(list.getFirstVisiblePosition() + i, list.getChildAt(i), list);
            }
            return;
        }
        mDisplayedSessions.clear();
        for (int i = 0; i < getCount(); i++) mDisplayedSessions.add(getItem(i));
        super.notifyDataSetChanged();
    }

    @SuppressLint("SetTextI18n")
    @NonNull
    @Override
    public View getView(int position, View convertView, @NonNull ViewGroup parent) {
        View sessionRowView = convertView;
        if (sessionRowView == null) {
            LayoutInflater inflater = mActivity.getLayoutInflater();
            sessionRowView = inflater.inflate(R.layout.item_terminal_sessions_list, parent, false);
        }

        TextView sessionNameView = sessionRowView.findViewById(R.id.session_name);
        TextView sessionTitleView = sessionRowView.findViewById(R.id.session_title);
        if (convertView == null) sessionTitleView.setSelected(true);
        TextView sessionNumberView = sessionRowView.findViewById(R.id.session_number);
        sessionNumberView.setText(String.valueOf(position + 1));
        sessionNumberView.setContentDescription(mActivity.getString(R.string.session_number_description, position + 1));

        TerminalSession sessionAtRow = getItem(position).getTerminalSession();
        if (sessionRowView.getTag() != sessionAtRow) sessionRowView.cancelPendingInputEvents();
        sessionRowView.setTag(sessionAtRow);
        // Keep card taps independent of ListView's deferred item-click handling.
        View card = sessionRowView;
        sessionRowView.findViewById(R.id.session_card_content).setOnClickListener(view -> {
            if (position < getCount() && sessionAtRow != null
                && getItem(position).getTerminalSession() == sessionAtRow) {
                ((ListView) parent).performItemClick(card, position, getItemId(position));
            }
        });
        String name = sessionAtRow == null ? null : sessionAtRow.mSessionName;
        String summary = sessionAtRow == null ? null : sessionAtRow.getTitle();
        sessionNameView.setText(name);
        sessionNameView.setVisibility(TextUtils.isEmpty(name) ? View.GONE : View.VISIBLE);
        // Output refreshes the drawer frequently; keep an unchanged marquee running.
        if (!TextUtils.equals(sessionTitleView.getText(), summary)) sessionTitleView.setText(summary);
        sessionTitleView.setVisibility(TextUtils.isEmpty(summary) ? View.GONE : View.VISIBLE);

        boolean current = sessionAtRow != null && sessionAtRow == mActivity.getCurrentSession();
        sessionRowView.setActivated(current);
        View menuButton = sessionRowView.findViewById(R.id.session_menu_button);
        menuButton.setEnabled(sessionAtRow != null);
        menuButton.setContentDescription(mActivity.getString(R.string.session_menu_description, position + 1));
        menuButton.setOnClickListener(view -> showSessionMenu(view, sessionAtRow));
        // State is available to screen readers without adding labels to the card.
        sessionRowView.setStateDescription(sessionAtRow == null ? null : mActivity.getString(
            !sessionAtRow.isRunning() ? R.string.session_status_finished
                : current ? R.string.session_status_current : R.string.session_status_running));
        return sessionRowView;
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        TermuxSession clickedSession = getItem(position);
        mActivity.getTermuxTerminalSessionClient().setCurrentSession(clickedSession.getTerminalSession());
        mActivity.getDrawer().closeDrawers();
    }

    private void showSessionMenu(View anchor, TerminalSession session) {
        if (session == null) return;

        PopupMenu menu = new PopupMenu(mActivity, anchor);
        menu.inflate(R.menu.menu_session);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_rename_session) {
                mActivity.getTermuxTerminalSessionClient().renameSession(session);
                return true;
            } else if (item.getItemId() == R.id.action_close_session) {
                mActivity.showCloseSessionDialog(session);
                return true;
            }
            return false;
        });
        menu.show();
    }

}
