package com.termux.app.terminal;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.function.IntSupplier;

/** One scrolling viewport for both drawer sections, including their headings. */
public final class TerminalDrawerRecyclerView extends RecyclerView {
    private IntSupplier positionToReveal;
    private final Runnable revealAfterLayout = this::revealPendingItem;

    public TerminalDrawerRecyclerView(Context context) {
        this(context, null);
    }

    public TerminalDrawerRecyclerView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TerminalDrawerRecyclerView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setLayoutManager(new LinearLayoutManager(context));
        setHasFixedSize(true);
        // Frequent terminal output must not animate or detach a card being tapped.
        setItemAnimator(null);
        setNestedScrollingEnabled(false);
        setVerticalScrollBarEnabled(false);
        int spacing = Math.round(8 * getResources().getDisplayMetrics().density);
        addItemDecoration(new ItemDecoration() {
            @Override
            public void getItemOffsets(@NonNull Rect outRect, @NonNull View view,
                                       @NonNull RecyclerView parent, @NonNull State state) {
                int position = parent.getChildAdapterPosition(view);
                Adapter<?> adapter = parent.getAdapter();
                outRect.set(0, 0, 0, adapter instanceof TerminalDrawerAdapter
                    && ((TerminalDrawerAdapter) adapter).hasDividerAfter(position) ? spacing : 0);
            }
        });
    }

    /** Resolve the position after updates, so insertions cannot redirect a pending reveal. */
    public void revealItem(IntSupplier position) {
        positionToReveal = position;
        scheduleReveal();
    }

    public void cancelPendingReveal() {
        positionToReveal = null;
        removeCallbacks(revealAfterLayout);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        scheduleReveal();
    }

    @Override
    public void onScrollStateChanged(int state) {
        super.onScrollStateChanged(state);
        if (state == SCROLL_STATE_DRAGGING) cancelPendingReveal();
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelPendingReveal();
        super.onDetachedFromWindow();
    }

    private void scheduleReveal() {
        removeCallbacks(revealAfterLayout);
        if (positionToReveal != null) post(revealAfterLayout);
    }

    private void revealPendingItem() {
        if (positionToReveal == null || getVisibility() != VISIBLE || isLayoutRequested()
            || isComputingLayout() || getHeight() == 0) return;
        int position = positionToReveal.getAsInt();
        if (position < 0 || getAdapter() == null || position >= getAdapter().getItemCount()) {
            cancelPendingReveal();
            return;
        }
        LinearLayoutManager layout = (LinearLayoutManager) getLayoutManager();
        if (layout == null) return;
        View row = layout.findViewByPosition(position);
        if (row == null) {
            // Materialize just the destination viewport, never the intervening cards.
            layout.scrollToPosition(position);
            return;
        }
        if (row.isLayoutRequested() || row.getHeight() == 0) return;

        int delta = 0;
        if (row.getTop() < getPaddingTop()) {
            delta = row.getTop() - getPaddingTop();
        } else if (row.getBottom() > getHeight() - getPaddingBottom()) {
            // Bottom padding keeps the selected card above the fixed new-session FAB.
            delta = row.getBottom() - getHeight() + getPaddingBottom();
        }
        cancelPendingReveal();
        if (delta != 0) scrollBy(0, delta);
    }
}
