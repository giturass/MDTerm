package com.termux.app.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.view.ViewCompat;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.termux.R;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** A scrolling Material action list that preserves menu enabled and checked states. */
public final class MaterialMenuDialog {
    private MaterialMenuDialog() { }

    public static AlertDialog show(Context context, CharSequence title, Menu menu,
                                   Consumer<MenuItem> action, Runnable onDismiss) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context);
        List<MenuItem> items = new ArrayList<>();
        for (int i = 0; i < menu.size(); i++) {
            if (menu.getItem(i).isVisible()) items.add(menu.getItem(i));
        }
        BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return items.size(); }
            @Override public MenuItem getItem(int position) { return items.get(position); }
            @Override public long getItemId(int position) { return getItem(position).getItemId(); }
            @Override public boolean areAllItemsEnabled() { return false; }
            @Override public boolean isEnabled(int position) { return getItem(position).isEnabled(); }
            @Override public View getView(int position, View recycled, ViewGroup parent) {
                View row = recycled == null ? LayoutInflater.from(builder.getContext())
                    .inflate(R.layout.material_action_row, parent, false) : recycled;
                MenuItem item = getItem(position);
                ((TextView) row.findViewById(android.R.id.title)).setText(item.getTitle());
                ImageView icon = row.findViewById(android.R.id.icon);
                icon.setImageDrawable(item.getIcon());
                icon.setImageTintList(ColorStateList.valueOf(MaterialColors.getColor(row,
                    com.google.android.material.R.attr.colorOnSurfaceVariant)));
                MaterialSwitch toggle = row.findViewById(R.id.action_switch);
                toggle.setOnCheckedChangeListener(null);
                toggle.setVisibility(item.isCheckable() ? View.VISIBLE : View.GONE);
                toggle.setChecked(item.isChecked());
                toggle.setEnabled(item.isEnabled());
                toggle.setContentDescription(item.getTitle());
                toggle.setOnCheckedChangeListener((button, checked) -> {
                    if (!item.isEnabled() || checked == item.isChecked()) return;
                    item.setChecked(checked);
                    action.accept(item);
                    notifyDataSetChanged();
                });
                row.setEnabled(item.isEnabled());
                row.setAlpha(item.isEnabled() ? 1f : 0.38f);
                ViewCompat.setStateDescription(row, item.isCheckable() ? context.getString(
                    item.isChecked() ? R.string.action_state_on : R.string.action_state_off) : null);
                return row;
            }
        };
        AlertDialog dialog = builder.setTitle(title).setAdapter(adapter, null)
            .setNegativeButton(android.R.string.cancel, null).create();
        if (onDismiss != null) dialog.setOnDismissListener(d -> onDismiss.run());
        dialog.show();
        dialog.getListView().setOnItemClickListener((parent, view, position, id) -> {
            MenuItem item = items.get(position);
            if (!item.isEnabled()) return;
            if (item.isCheckable()) {
                item.setChecked(!item.isChecked());
                action.accept(item);
                adapter.notifyDataSetChanged();
            } else {
                action.accept(item);
                dialog.dismiss();
            }
        });
        return dialog;
    }
}
