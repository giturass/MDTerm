package com.termux.app.fragments.settings;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.Keep;
import androidx.preference.SwitchPreferenceCompat;

import com.termux.R;

/** Uses the real Material 3 switch while retaining AndroidX persistence and accessibility. */
@Keep
public class MaterialSwitchPreference extends SwitchPreferenceCompat {

    public MaterialSwitchPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWidgetLayoutResource(R.layout.settings_widget_material_switch);
    }

    public MaterialSwitchPreference(Context context) {
        this(context, null);
    }
}
