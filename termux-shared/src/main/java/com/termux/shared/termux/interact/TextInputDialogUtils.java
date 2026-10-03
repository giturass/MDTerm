package com.termux.shared.termux.interact;

import android.app.Activity;
import android.content.DialogInterface;
import android.text.Selection;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import com.termux.shared.R;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public final class TextInputDialogUtils {

    public interface TextSetListener {
        void onTextSet(String text);
    }

    public static void textInput(Activity activity, int titleText, String initialText,
                                 int positiveButtonText, final TextSetListener onPositive,
                                 int neutralButtonText, final TextSetListener onNeutral,
                                 int negativeButtonText, final TextSetListener onNegative,
                                 final DialogInterface.OnDismissListener onDismiss) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity);
        View layout = LayoutInflater.from(builder.getContext()).inflate(R.layout.dialog_text_input, null);
        final EditText input = layout.findViewById(R.id.dialog_text_input);
        input.setContentDescription(activity.getString(titleText));
        if (initialText != null) {
            input.setText(initialText);
            Selection.setSelection(input.getText(), initialText.length());
        }

        final AlertDialog[] dialogHolder = new AlertDialog[1];
        input.setImeActionLabel(activity.getResources().getString(positiveButtonText), EditorInfo.IME_ACTION_DONE);
        input.setOnEditorActionListener((v, actionId, event) -> {
            if (event != null && event.getAction() != KeyEvent.ACTION_DOWN) return true;
            onPositive.onTextSet(input.getText().toString());
            dialogHolder[0].dismiss();
            return true;
        });

        builder.setTitle(titleText).setView(layout)
            .setPositiveButton(positiveButtonText, (d, whichButton) -> onPositive.onTextSet(input.getText().toString()));

        if (onNeutral != null) {
            builder.setNeutralButton(neutralButtonText, (dialog, which) -> onNeutral.onTextSet(input.getText().toString()));
        }

        if (onNegative == null) {
            builder.setNegativeButton(android.R.string.cancel, null);
        } else {
            builder.setNegativeButton(negativeButtonText, (dialog, which) -> onNegative.onTextSet(input.getText().toString()));
        }

        if (onDismiss != null)
            builder.setOnDismissListener(onDismiss);

        dialogHolder[0] = builder.create();
        dialogHolder[0].setCanceledOnTouchOutside(false);
        input.requestFocus();
        dialogHolder[0].getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        dialogHolder[0].show();
    }

}
