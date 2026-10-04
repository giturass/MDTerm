package com.termux.app.terminal;

import android.annotation.SuppressLint;
import android.content.res.ColorStateList;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.google.android.material.color.MaterialColors;
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
        TextView sessionStatusView = sessionRowView.findViewById(R.id.session_status);
        TextView sessionNumberView = sessionRowView.findViewById(R.id.session_number);
        sessionNumberView.setText(String.valueOf(position + 1));
        sessionNumberView.setContentDescription(mActivity.getString(R.string.session_number_description, position + 1));

        TerminalSession sessionAtRow = getItem(position).getTerminalSession();
        String name = sessionAtRow == null ? null : sessionAtRow.mSessionName;
        String summary = sessionAtRow == null ? null : sessionAtRow.getTitle();
        sessionNameView.setText(TextUtils.isEmpty(name)
            ? mActivity.getString(R.string.session_number_description, position + 1) : name);
        sessionTitleView.setText(summary);
        sessionTitleView.setVisibility(TextUtils.isEmpty(summary) ? View.GONE : View.VISIBLE);

        boolean current = sessionAtRow != null && sessionAtRow == mActivity.getCurrentSession();
        sessionRowView.setActivated(current);
        bindSessionStatus(sessionStatusView, sessionAtRow, current);
        return sessionRowView;
    }

    private void bindSessionStatus(TextView statusView, TerminalSession session, boolean current) {
        int backgroundAttr;
        int textAttr;
        if (session == null) {
            statusView.setText(R.string.session_status_starting);
            backgroundAttr = com.google.android.material.R.attr.colorSurfaceContainerHighest;
            textAttr = com.google.android.material.R.attr.colorOnSurfaceVariant;
        } else if (!session.isRunning()) {
            boolean failed = session.getExitStatus() != 0;
            statusView.setText(failed
                ? mActivity.getString(R.string.session_status_failed, session.getExitStatus())
                : mActivity.getString(R.string.session_status_finished));
            backgroundAttr = failed ? com.google.android.material.R.attr.colorErrorContainer
                : com.google.android.material.R.attr.colorSurfaceContainerHighest;
            textAttr = failed ? com.google.android.material.R.attr.colorOnErrorContainer
                : com.google.android.material.R.attr.colorOnSurfaceVariant;
        } else {
            statusView.setText(current ? R.string.session_status_current : R.string.session_status_running);
            backgroundAttr = current ? com.google.android.material.R.attr.colorPrimary
                : com.google.android.material.R.attr.colorTertiaryContainer;
            textAttr = current ? com.google.android.material.R.attr.colorOnPrimary
                : com.google.android.material.R.attr.colorOnTertiaryContainer;
        }
        int textColor = MaterialColors.getColor(statusView, textAttr);
        statusView.setTextColor(textColor);
        statusView.setCompoundDrawableTintList(ColorStateList.valueOf(textColor));
        statusView.setBackgroundTintList(ColorStateList.valueOf(MaterialColors.getColor(statusView, backgroundAttr)));
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
