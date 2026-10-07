package com.termux.app.fragments.settings;

import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;
import androidx.preference.PreferenceGroupAdapter;
import androidx.preference.PreferenceScreen;
import androidx.preference.PreferenceViewHolder;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;
import com.termux.R;
import com.termux.app.activities.SettingsActivity;

/** Shared Material surfaces, navigation rows and dialogs for every settings screen. */
public abstract class MaterialPreferenceFragment extends PreferenceFragmentCompat {

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setDivider(null);
        RecyclerView list = getListView();
        list.setClipToPadding(false);
        list.setPadding(0, dp(4), 0, dp(16));
        list.setItemAnimator(null);
        list.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(@NonNull Rect outRect, @NonNull View child,
                                       @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
                RecyclerView.Adapter<?> adapter = parent.getAdapter();
                int position = parent.getChildAdapterPosition(child);
                if (!(adapter instanceof PreferenceGroupAdapter) || position == RecyclerView.NO_POSITION) return;
                Preference item = ((PreferenceGroupAdapter) adapter).getItem(position);
                boolean category = item instanceof PreferenceCategory;
                outRect.set(dp(12), category ? dp(14) : dp(1), dp(12), category ? dp(6) : dp(1));
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getActivity() instanceof SettingsActivity && getPreferenceScreen() != null) {
            ((SettingsActivity) getActivity()).showSettingsHeader(
                getPreferenceScreen().getTitle(), getPreferenceScreen().getSummary());
        }
    }

    @Override
    protected RecyclerView.Adapter onCreateAdapter(PreferenceScreen screen) {
        stylePreferences(screen);
        return new PreferenceGroupAdapter(screen) {
            @Override
            public void onBindViewHolder(@NonNull PreferenceViewHolder holder, int position) {
                super.onBindViewHolder(holder, position);
                Preference item = getItem(position);
                holder.setDividerAllowedAbove(false);
                holder.setDividerAllowedBelow(false);
                if (item instanceof PreferenceCategory) return;
                boolean first = position == 0 || !sameGroup(item, getItem(position - 1));
                boolean last = position == getItemCount() - 1 || !sameGroup(item, getItem(position + 1));
                float top = dp(first ? 20 : 4);
                float bottom = dp(last ? 20 : 4);
                float[] corners = {top, top, top, top, bottom, bottom, bottom, bottom};
                GradientDrawable surface = new GradientDrawable();
                surface.setColor(MaterialColors.getColor(holder.itemView,
                    com.google.android.material.R.attr.colorSurfaceContainer));
                surface.setCornerRadii(corners);
                GradientDrawable mask = new GradientDrawable();
                mask.setColor(android.graphics.Color.WHITE);
                mask.setCornerRadii(corners);
                int ripple = ColorUtils.setAlphaComponent(MaterialColors.getColor(holder.itemView,
                    com.google.android.material.R.attr.colorPrimary), 28);
                holder.itemView.setBackground(new RippleDrawable(ColorStateList.valueOf(ripple), surface, mask));
                ImageView icon = (ImageView) holder.findViewById(android.R.id.icon);
                if (icon != null) icon.setImageTintList(ColorStateList.valueOf(MaterialColors.getColor(
                    holder.itemView, com.google.android.material.R.attr.colorPrimary)));
            }
        };
    }

    private static boolean sameGroup(Preference item, Preference neighbor) {
        return neighbor != null && !(neighbor instanceof PreferenceCategory) && item.getParent() == neighbor.getParent();
    }

    private void stylePreferences(PreferenceGroup group) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference preference = group.getPreference(i);
            preference.setIconSpaceReserved(false);
            if (preference instanceof PreferenceCategory) {
                preference.setLayoutResource(R.layout.settings_preference_category);
            } else {
                preference.setLayoutResource(R.layout.settings_preference_row);
                preference.setSingleLineTitle(false);
                if (!(preference instanceof MaterialSwitchPreference)) {
                    preference.setWidgetLayoutResource(!preference.isSelectable() ? 0 :
                        preference instanceof ListPreference ? R.layout.settings_widget_dropdown :
                        R.layout.settings_widget_chevron);
                }
            }
            if (preference instanceof PreferenceGroup) stylePreferences((PreferenceGroup) preference);
        }
    }

    @Override
    public void onDisplayPreferenceDialog(@NonNull Preference preference) {
        if (preference instanceof ListPreference || preference instanceof EditTextPreference) {
            String tag = "mdterm.preference.dialog";
            if (getChildFragmentManager().findFragmentByTag(tag) == null) {
                if (preference instanceof EditTextPreference) {
                    MaterialEditTextPreferenceDialog.newInstance(preference.getKey()).show(getChildFragmentManager(), tag);
                } else {
                    MaterialListPreferenceDialog.newInstance(preference.getKey()).show(getChildFragmentManager(), tag);
                }
            }
        } else {
            super.onDisplayPreferenceDialog(preference);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
