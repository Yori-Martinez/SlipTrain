package es.yori.sleeptrain;

import android.animation.ValueAnimator;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

final class ViewMotion {
    private ViewMotion() { }

    static void enter(View view, int distanceDp) {
        if (!ValueAnimator.areAnimatorsEnabled()) return;
        view.animate().cancel();
        view.setAlpha(.65f);
        view.setTranslationY(distanceDp * view.getResources().getDisplayMetrics().density);
        view.animate().alpha(1f).translationY(0).setDuration(150).setInterpolator(new DecelerateInterpolator()).start();
    }
}
