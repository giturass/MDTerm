package com.termux.app.terminal;

import android.annotation.SuppressLint;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;

import java.util.List;

public class TermuxSessionsListViewController extends ArrayAdapter<TermuxSession> implements AdapterView.OnItemClickListener, AdapterView.OnItemLongClickListener {

    final TermuxActivity mActivity;

    public TermuxSessionsListViewController(TermuxActivity activity, List<TermuxSession> sessionList) {
        super(activity.getApplicationContext(), R.layout.item_terminal_sessions_list, sessionList);
        this.mActivity = activity;
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
        TextView sessionNumberView = sessionRowView.findViewById(R.id.session_number);
        sessionNumberView.setText(String.valueOf(position + 1));
        sessionNumberView.setContentDescription(mActivity.getString(R.string.session_number_description, position + 1));

        TerminalSession sessionAtRow = getItem(position).getTerminalSession();
        String name = sessionAtRow == null ? null : sessionAtRow.mSessionName;
        String summary = sessionAtRow == null ? null : sessionAtRow.getTitle();
        sessionNameView.setText(name);
        sessionNameView.setVisibility(TextUtils.isEmpty(name) ? View.GONE : View.VISIBLE);
        sessionTitleView.setText(summary);
        sessionTitleView.setVisibility(TextUtils.isEmpty(summary) ? View.GONE : View.VISIBLE);

        boolean current = sessionAtRow != null && sessionAtRow == mActivity.getCurrentSession();
        sessionRowView.setActivated(current);
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

    @Override
    public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
        final TermuxSession selectedSession = getItem(position);
        mActivity.getTermuxTerminalSessionClient().renameSession(selectedSession.getTerminalSession());
        return true;
    }

}
