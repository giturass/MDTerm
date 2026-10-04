package com.termux.app.terminal;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

import androidx.annotation.Nullable;

import com.termux.R;

/** A steady activity dot with expanding ripples while the session card is visible. */
public final class SessionActivityIndicator extends View {

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ValueAnimator mAnimator = ValueAnimator.ofFloat(0f, 1f);
    private final float mDensity;
    private boolean mActive;
    private float mProgress;

    public SessionActivityIndicator(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        mDensity = getResources().getDisplayMetrics().density;
        mPaint.setColor(context.getColor(R.color.session_activity_green));
        mAnimator.setDuration(1800);
        mAnimator.setRepeatCount(ValueAnimator.INFINITE);
        mAnimator.setInterpolator(new LinearInterpolator());
        mAnimator.addUpdateListener(animation -> {
            mProgress = (float) animation.getAnimatedValue();
            invalidate();
        });
    }

    public void setActive(boolean active) {
        mActive = active;
        setVisibility(active ? VISIBLE : GONE);
        updateAnimation();
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updateAnimation();
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        if (isVisible) updateAnimation();
        else stopAnimation();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        updateAnimation();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopAnimation();
        super.onDetachedFromWindow();
    }

    private void updateAnimation() {
        if (mActive && isAttachedToWindow() && isShown() && getWindowVisibility() == VISIBLE
            && ValueAnimator.areAnimatorsEnabled()) {
            if (!mAnimator.isStarted()) mAnimator.start();
        } else {
            stopAnimation();
        }
    }

    private void stopAnimation() {
        mAnimator.cancel();
        mProgress = 0f;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!mActive) return;

        float centerX = getWidth() / 2f;
        float centerY = getHeight() / 2f;
        float dotRadius = 3f * mDensity;
        float rippleRadius = Math.min(centerX, centerY) - mDensity;
        if (mAnimator.isStarted()) {
            drawRipple(canvas, centerX, centerY, dotRadius, rippleRadius, mProgress);
            drawRipple(canvas, centerX, centerY, dotRadius, rippleRadius, (mProgress + 0.5f) % 1f);
        }

        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setAlpha(255);
        canvas.drawCircle(centerX, centerY, dotRadius, mPaint);
    }

    private void drawRipple(Canvas canvas, float x, float y, float startRadius, float endRadius, float progress) {
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeWidth(mDensity);
        mPaint.setAlpha(Math.round(100 * (1f - progress)));
        canvas.drawCircle(x, y, startRadius + (endRadius - startRadius) * progress, mPaint);
    }
}
