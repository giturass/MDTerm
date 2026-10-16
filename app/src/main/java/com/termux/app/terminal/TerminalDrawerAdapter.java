package com.termux.app.terminal;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.interact.TextInputDialogUtils;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bookmarks, sessions and section headings share one recycled list. */
public final class TerminalDrawerAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    public interface OnBookmarkClickListener {
        void onBookmarkClick(TerminalBookmark bookmark);
    }

    private static final int HEADER = 0;
    private static final int BOOKMARK = 1;
    private static final int SESSION = 2;
    private static final Object CONTENT_CHANGED = new Object();
    private static final long SUMMARY_IDLE_DELAY_MS = 2000;

    private final TermuxActivity activity;
    private final TerminalBookmarkStore store;
    private final OnBookmarkClickListener bookmarkClickListener;
    private final TerminalDrawerRecyclerView list;
    private final Handler summaryHandler = new Handler(Looper.getMainLooper());
    private final Map<TerminalSession, Runnable> summaryIdleCallbacks = new HashMap<>();
    private final Map<Object, Long> itemIds = new HashMap<>();
    private long nextItemId = 2; // The two headings have IDs 0 and 1.
    private List<TermuxSession> sessions = Collections.emptyList();
    private List<TerminalBookmark> bookmarks;
    private List<Row> rows = Collections.emptyList();
    private boolean disposed;

    public TerminalDrawerAdapter(TermuxActivity activity, TerminalBookmarkStore store,
                                 OnBookmarkClickListener listener) {
        this.activity = activity;
        this.store = store;
        bookmarkClickListener = listener;
        bookmarks = store.getAll();
        setHasStableIds(true);
        rows = snapshotRows();
        list = activity.findViewById(R.id.terminal_drawer_list);
        list.setAdapter(this);
    }

    public void setSessions(List<TermuxSession> sessions) {
        this.sessions = sessions;
        refreshSessions();
    }

    public void refreshSessions() {
        Set<TerminalSession> running = new HashSet<>();
        for (TermuxSession session : sessions) {
            TerminalSession terminal = session.getTerminalSession();
            if (terminal != null && terminal.isRunning()) running.add(terminal);
        }
        Iterator<Map.Entry<TerminalSession, Runnable>> iterator = summaryIdleCallbacks.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<TerminalSession, Runnable> entry = iterator.next();
            if (!running.contains(entry.getKey())) {
                summaryHandler.removeCallbacks(entry.getValue());
                iterator.remove();
            }
        }
        submitRows();
    }

    public void refreshBookmarks() {
        bookmarks = store.getAll();
        submitRows();
    }

    public void refreshAndRevealBookmark(String bookmarkId) {
        refreshBookmarks();
        list.revealItem(() -> findBookmarkPosition(bookmarkId));
    }

    public int getBookmarkCount() {
        return bookmarks.size();
    }

    public TerminalBookmark getBookmark(int index) {
        return bookmarks.get(index);
    }

    public int findBookmarkPosition(String bookmarkId) {
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (row.bookmark != null && row.bookmark.id.equals(bookmarkId)) return i;
        }
        return RecyclerView.NO_POSITION;
    }

    public int findSessionPosition(TerminalSession session) {
        if (session == null) return RecyclerView.NO_POSITION;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).session == session) return i;
        }
        return RecyclerView.NO_POSITION;
    }

    public void revealSession(TerminalSession session) {
        list.revealItem(() -> findSessionPosition(session));
    }

    boolean hasDividerAfter(int position) {
        return hasDividerAfter(rows, position);
    }

    private static boolean hasDividerAfter(List<Row> rows, int position) {
        return position >= 0 && position + 1 < rows.size()
            && rows.get(position).type != HEADER
            && rows.get(position).type == rows.get(position + 1).type;
    }

    /** Output and title changes pause only this session's summary until it stays quiet. */
    public void onSessionActivity(TerminalSession session) {
        if (disposed || session == null || !session.isRunning()) return;
        Runnable idle = summaryIdleCallbacks.get(session);
        if (idle == null) {
            idle = () -> {
                summaryIdleCallbacks.remove(session);
                updateSummaryScrolling(session);
            };
            summaryIdleCallbacks.put(session, idle);
            updateSummaryScrolling(session);
        } else {
            summaryHandler.removeCallbacks(idle);
        }
        summaryHandler.postDelayed(idle, SUMMARY_IDLE_DELAY_MS);
    }

    public void dispose() {
        disposed = true;
        summaryHandler.removeCallbacksAndMessages(null);
        summaryIdleCallbacks.clear();
        list.cancelPendingReveal();
    }

    private void updateSummaryScrolling(TerminalSession session) {
        for (int i = 0; i < list.getChildCount(); i++) {
            View row = list.getChildAt(i);
            if (row.getTag() == session) {
                bindSummaryScrolling(row.findViewById(R.id.session_title), session);
            }
        }
    }

    private void bindSummaryScrolling(TextView summary, TerminalSession session) {
        if (disposed) return;
        boolean scroll = session != null && (!session.isRunning() || !summaryIdleCallbacks.containsKey(session));
        TextUtils.TruncateAt ellipsize = scroll ? TextUtils.TruncateAt.MARQUEE : TextUtils.TruncateAt.END;
        if (!scroll && summary.isSelected()) summary.setSelected(false);
        if (summary.getEllipsize() != ellipsize) summary.setEllipsize(ellipsize);
        if (scroll && !summary.isSelected()) summary.setSelected(true);
    }

    private List<Row> snapshotRows() {
        List<Row> result = new ArrayList<>(bookmarks.size() + sessions.size() + 2);
        Set<Object> retainedIds = new HashSet<>();
        result.add(Row.header(0, R.id.terminal_bookmarks_header, R.string.terminal_bookmarks_header));
        for (TerminalBookmark bookmark : bookmarks) {
            retainedIds.add(bookmark.id);
            String environment = "proot".equals(bookmark.kind) ? bookmark.distro
                : "ssh".equals(bookmark.kind) ? (bookmark.sshArgs.isEmpty() ? "SSH"
                    : bookmark.sshArgs.get(bookmark.sshArgs.size() - 1)) : "";
            String displayPath = bookmark.path;
            String homePath = TermuxConstants.TERMUX_HOME_DIR_PATH;
            if ("local".equals(bookmark.kind)
                && (displayPath.equals(homePath) || displayPath.startsWith(homePath + "/"))) {
                displayPath = "~" + displayPath.substring(homePath.length());
            }
            String label = environment.isEmpty() ? displayPath : environment + " · " + displayPath;
            result.add(new Row(BOOKMARK, itemId(bookmark.id), bookmark, null, 0,
                bookmark.name, label, false, false, 0, 0));
        }
        result.add(Row.header(1, R.id.terminal_sessions_header, R.string.terminal_sessions_header));
        for (int i = 0; i < sessions.size(); i++) {
            TermuxSession item = sessions.get(i);
            retainedIds.add(item);
            TerminalSession session = item.getTerminalSession();
            result.add(new Row(SESSION, itemId(item), null, session, i + 1,
                session == null ? null : session.mSessionName, session == null ? null : session.getTitle(),
                session != null && session == activity.getCurrentSession(),
                session != null && session.isRunning(), 0, 0));
        }
        itemIds.keySet().retainAll(retainedIds);
        return result;
    }

    private long itemId(Object key) {
        Long id = itemIds.get(key);
        if (id == null) {
            id = nextItemId++;
            itemIds.put(key, id);
        }
        return id;
    }

    private void submitRows() {
        List<Row> previous = rows;
        List<Row> updated = snapshotRows();
        DiffUtil.DiffResult changes = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return previous.size(); }
            @Override public int getNewListSize() { return updated.size(); }
            @Override public boolean areItemsTheSame(int oldPosition, int newPosition) {
                return previous.get(oldPosition).id == updated.get(newPosition).id;
            }
            @Override public boolean areContentsTheSame(int oldPosition, int newPosition) {
                return previous.get(oldPosition).sameContent(updated.get(newPosition))
                    && hasDividerAfter(previous, oldPosition) == hasDividerAfter(updated, newPosition);
            }
            @Override public Object getChangePayload(int oldPosition, int newPosition) {
                return CONTENT_CHANGED;
            }
        }, false);
        rows = updated;
        changes.dispatchUpdatesTo(this);
    }

    @Override public int getItemCount() { return rows.size(); }
    @Override public int getItemViewType(int position) { return rows.get(position).type; }
    @Override public long getItemId(int position) { return rows.get(position).id; }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == HEADER) {
            return new HeaderHolder(activity.getLayoutInflater().inflate(
                R.layout.item_terminal_drawer_header, parent, false));
        }
        return new CardHolder(activity.getLayoutInflater().inflate(
            R.layout.item_terminal_sessions_list, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Row row = rows.get(position);
        if (holder instanceof HeaderHolder) {
            TextView title = ((HeaderHolder) holder).title;
            title.setId(row.headerId);
            title.setText(row.headerText);
            return;
        }
        CardHolder card = (CardHolder) holder;
        if (card.boundId != row.id) card.itemView.cancelPendingInputEvents();
        card.boundId = row.id;
        card.itemView.setTag(row.type == BOOKMARK ? row.bookmark.id : row.session);
        card.itemView.setActivated(row.current);
        if (!TextUtils.equals(card.name.getText(), row.name)) card.name.setText(row.name);
        card.name.setVisibility(TextUtils.isEmpty(row.name) ? View.GONE : View.VISIBLE);
        if (row.type == BOOKMARK) {
            card.number.setText("★");
            card.number.setContentDescription(activity.getString(R.string.terminal_bookmark_description));
            if (card.summary.getEllipsize() != TextUtils.TruncateAt.MARQUEE)
                card.summary.setEllipsize(TextUtils.TruncateAt.MARQUEE);
            if (!card.summary.isSelected()) card.summary.setSelected(true);
            card.menu.setEnabled(true);
            card.menu.setContentDescription(activity.getString(R.string.bookmark_menu_description, row.name));
            card.itemView.setStateDescription(null);
        } else {
            card.number.setText(String.valueOf(row.number));
            card.number.setContentDescription(activity.getString(R.string.session_number_description, row.number));
            bindSummaryScrolling(card.summary, row.session);
            card.menu.setEnabled(row.session != null);
            card.menu.setContentDescription(activity.getString(R.string.session_menu_description, row.number));
            card.itemView.setStateDescription(row.session == null ? null : activity.getString(
                !row.running ? R.string.session_status_finished
                    : row.current ? R.string.session_status_current : R.string.session_status_running));
        }
        // Preserve a marquee through unrelated output, selection and name changes.
        if (!TextUtils.equals(card.summary.getText(), row.summary)) card.summary.setText(row.summary);
        card.summary.setVisibility(TextUtils.isEmpty(row.summary) ? View.GONE : View.VISIBLE);
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        if (holder instanceof CardHolder) {
            CardHolder card = (CardHolder) holder;
            card.boundId = RecyclerView.NO_ID;
            card.itemView.cancelPendingInputEvents();
            card.itemView.setTag(null);
            card.summary.setSelected(false);
        }
        super.onViewRecycled(holder);
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        dispose();
        super.onDetachedFromRecyclerView(recyclerView);
    }

    private Row clickedRow(CardHolder holder) {
        int position = holder.getAdapterPosition();
        if (disposed || position == RecyclerView.NO_POSITION || position >= rows.size()) return null;
        Row row = rows.get(position);
        // A removal can arrive between ACTION_DOWN and the next layout pass.
        return row.id == holder.boundId ? row : null;
    }

    private void openCard(CardHolder holder) {
        Row row = clickedRow(holder);
        if (row == null) return;
        if (row.type == BOOKMARK) {
            bookmarkClickListener.onBookmarkClick(row.bookmark);
        } else if (row.session != null) {
            activity.getTermuxTerminalSessionClient().setCurrentSession(row.session);
            activity.getDrawer().closeDrawers();
        }
    }

    private void showMenu(CardHolder holder) {
        Row row = clickedRow(holder);
        if (row == null) return;
        if (row.type == BOOKMARK) showBookmarkMenu(holder.menu, row.bookmark);
        else if (row.session != null) showSessionMenu(holder.menu, row.session);
    }

    private void showSessionMenu(View anchor, TerminalSession session) {
        PopupMenu menu = new PopupMenu(activity, anchor);
        menu.inflate(R.menu.menu_session);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_rename_session) {
                activity.getTermuxTerminalSessionClient().renameSession(session);
                return true;
            } else if (item.getItemId() == R.id.action_close_session) {
                activity.showCloseSessionDialog(session);
                return true;
            }
            return false;
        });
        menu.show();
    }

    private void showBookmarkMenu(View anchor, TerminalBookmark bookmark) {
        PopupMenu menu = new PopupMenu(activity, anchor);
        menu.getMenu().add(0, 1, 0, R.string.action_rename_bookmark);
        menu.getMenu().add(0, 2, 1, R.string.action_delete_bookmark);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                TextInputDialogUtils.textInput(activity, R.string.action_rename_bookmark,
                    bookmark.name, R.string.action_rename_session_confirm, text -> {
                        String name = text == null ? "" : text.trim();
                        if (!name.isEmpty()) {
                            store.rename(bookmark.id, name);
                            refreshBookmarks();
                        }
                    }, -1, null, -1, null, null);
                return true;
            }
            if (item.getItemId() == 2) {
                new MaterialAlertDialogBuilder(activity)
                    .setMessage(activity.getString(R.string.message_confirm_delete_bookmark, bookmark.name))
                    .setPositiveButton(R.string.action_delete_bookmark, (dialog, which) -> {
                        store.delete(bookmark.id);
                        refreshBookmarks();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
                return true;
            }
            return false;
        });
        menu.show();
    }

    private static final class HeaderHolder extends RecyclerView.ViewHolder {
        final TextView title;

        HeaderHolder(View view) {
            super(view);
            title = view.findViewById(R.id.terminal_drawer_section_title);
            ViewCompat.setAccessibilityHeading(title, true);
        }
    }

    private final class CardHolder extends RecyclerView.ViewHolder {
        final TextView number;
        final TextView name;
        final TextView summary;
        final View menu;
        long boundId = RecyclerView.NO_ID;

        CardHolder(View view) {
            super(view);
            number = view.findViewById(R.id.session_number);
            name = view.findViewById(R.id.session_name);
            summary = view.findViewById(R.id.session_title);
            menu = view.findViewById(R.id.session_menu_button);
            view.findViewById(R.id.session_card_content).setOnClickListener(ignored -> openCard(this));
            menu.setOnClickListener(ignored -> showMenu(this));
        }
    }

    /** Capture mutable session labels before diffing, so output changes remain detectable. */
    private static final class Row {
        final int type;
        final long id;
        final TerminalBookmark bookmark;
        final TerminalSession session;
        final int number;
        final String name;
        final String summary;
        final boolean current;
        final boolean running;
        final int headerId;
        final int headerText;

        Row(int type, long id, TerminalBookmark bookmark, TerminalSession session, int number,
            String name, String summary, boolean current, boolean running, int headerId, int headerText) {
            this.type = type;
            this.id = id;
            this.bookmark = bookmark;
            this.session = session;
            this.number = number;
            this.name = name;
            this.summary = summary;
            this.current = current;
            this.running = running;
            this.headerId = headerId;
            this.headerText = headerText;
        }

        static Row header(long id, int viewId, int textId) {
            return new Row(HEADER, id, null, null, 0, null, null, false, false, viewId, textId);
        }

        boolean sameContent(Row other) {
            return number == other.number && current == other.current && running == other.running
                && TextUtils.equals(name, other.name) && TextUtils.equals(summary, other.summary);
        }
    }
}
