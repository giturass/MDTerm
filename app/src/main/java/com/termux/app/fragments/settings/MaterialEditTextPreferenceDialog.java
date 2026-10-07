package com.termux.app.fragments.settings;

import android.app.Dialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.preference.EditTextPreference;
import androidx.preference.PreferenceFragmentCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.termux.R;

/** Keeps invalid values editable and only dismisses after the change listener saves successfully. */
@Keep
public class MaterialEditTextPreferenceDialog extends DialogFragment {

    static final String INPUT_TYPE = "mdterm.input_type";
    static final String INPUT_HINT = "mdterm.input_hint";
    static final String VALIDATION_ERROR = "mdterm.validation_error";

    private EditTextPreference preference;
    private TextInputLayout inputLayout;
    private TextInputEditText input;

    public static MaterialEditTextPreferenceDialog newInstance(String key) {
        MaterialEditTextPreferenceDialog dialog = new MaterialEditTextPreferenceDialog();
        Bundle args = new Bundle();
        args.putString("preference_key", key);
        dialog.setArguments(args);
        return dialog;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        PreferenceFragmentCompat owner = (PreferenceFragmentCompat) requireParentFragment();
        preference = owner.findPreference(requireArguments().getString("preference_key"));
        if (preference == null) throw new IllegalStateException("Missing edit text preference");

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext());
        LinearLayout container = new LinearLayout(builder.getContext());
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        container.setPadding(padding, padding / 2, padding, 0);
        inputLayout = new TextInputLayout(builder.getContext());
        inputLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        inputLayout.setHint(preference.getExtras().getString(INPUT_HINT));
        input = new TextInputEditText(inputLayout.getContext());
        input.setId(android.R.id.edit);
        input.setSingleLine(true);
        input.setInputType(preference.getExtras().getInt(INPUT_TYPE, InputType.TYPE_CLASS_TEXT));
        inputLayout.addView(input, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        container.addView(inputLayout, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        input.setText(savedInstanceState == null ? preference.getText()
            : savedInstanceState.getString("input_text"));
        input.setSelection(input.length());
        if (savedInstanceState != null) inputLayout.setError(savedInstanceState.getString("input_error"));

        return builder.setTitle(preference.getDialogTitle())
            .setMessage(preference.getDialogMessage())
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create();
    }

    @Override
    public void onStart() {
        super.onStart();
        AlertDialog dialog = (AlertDialog) requireDialog();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String value = input.getText() == null ? "" : input.getText().toString().trim();
            preference.getExtras().remove(VALIDATION_ERROR);
            if (preference.callChangeListener(value)) {
                preference.setText(value);
                dismiss();
            } else {
                inputLayout.setError(preference.getExtras().getString(VALIDATION_ERROR,
                    getString(R.string.mdterm_prop_invalid_value)));
            }
        });
        input.requestFocus();
        if (dialog.getWindow() != null) dialog.getWindow().setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (input != null) outState.putString("input_text", input.getText() == null ? "" : input.getText().toString());
        if (inputLayout != null && inputLayout.getError() != null)
            outState.putString("input_error", inputLayout.getError().toString());
    }
}
