package com.termux.app.fragments.settings;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import androidx.core.view.ViewCompat;
import com.google.android.material.color.MaterialColors;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import androidx.preference.ListPreference;
import androidx.preference.PreferenceFragmentCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.radiobutton.MaterialRadioButton;
import com.termux.R;

/** Restorable Material dialog; choosing an entry respects the preference change listener. */
@Keep
public class MaterialListPreferenceDialog extends DialogFragment {

    public static MaterialListPreferenceDialog newInstance(String key) {
        MaterialListPreferenceDialog dialog = new MaterialListPreferenceDialog();
        Bundle args = new Bundle();
        args.putString("preference_key", key);
        dialog.setArguments(args);
        return dialog;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        PreferenceFragmentCompat owner = (PreferenceFragmentCompat) requireParentFragment();
        ListPreference preference = owner.findPreference(requireArguments().getString("preference_key"));
        if (preference == null || preference.getEntries() == null || preference.getEntryValues() == null) {
            throw new IllegalStateException("List preference requires entries and values");
        }
        int selected = preference.findIndexOfValue(preference.getValue());
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext());
        ArrayAdapter<CharSequence> choices = new ArrayAdapter<CharSequence>(builder.getContext(),
            R.layout.settings_dialog_choice, preference.getEntries()) {
            @NonNull
            @Override
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                View row = convertView == null ? LayoutInflater.from(getContext())
                    .inflate(R.layout.settings_dialog_choice, parent, false) : convertView;
                boolean checked = position == selected;
                View surface = row.findViewById(R.id.choice_surface);
                surface.setActivated(checked);
                TextView title = row.findViewById(android.R.id.title);
                title.setText(getItem(position));
                title.setTextColor(MaterialColors.getColor(row, checked
                    ? com.google.android.material.R.attr.colorOnSecondaryContainer
                    : com.google.android.material.R.attr.colorOnSurface));
                MaterialRadioButton radio = row.findViewById(R.id.choice_radio);
                radio.setChecked(checked);
                ViewCompat.setStateDescription(row, checked ? getContext().getString(R.string.log_choice_selected) : null);
                return row;
            }
        };
        return builder.setTitle(preference.getDialogTitle())
            .setSingleChoiceItems(choices, selected, (dialog, which) -> {
                String value = preference.getEntryValues()[which].toString();
                if (preference.callChangeListener(value)) preference.setValue(value);
                dismiss();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .create();
    }
}
