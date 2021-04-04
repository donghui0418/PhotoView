package test.zoomabledrawee;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.support.annotation.Nullable;
import android.support.v4.view.GestureDetectorCompat;
import android.support.v4.widget.ScrollerCompat;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.Interpolator;

import com.facebook.drawee.drawable.ScalingUtils;
import com.facebook.drawee.generic.GenericDraweeHierarchy;
import com.facebook.drawee.view.DraweeView;

import java.lang.ref.WeakReference;

public class Attacher implements View.OnTouchListener, OnScaleDragGestureListener {

    private static final int TAKE_OVER_ON_EDGE = 0;
    private static final int TAKE_OVER_UNTIL_NEXT_DOWN = 1;

    private static final int DEFAULT_ZOOM_DURATION = 200;
    private static final int DEFAULT_ROTATE_DURATION = 200;

    private static final int DEGREE_X = -1;
    private static final int DEGREE_0 = 0;
    private static final int DEGREE_90 = 1;
    private static final int DEGREE_180 = 2;
    private static final int DEGREE_270 = 3;
    private static final float DEFAULT_COMPENSATE_SCALE = 1f;

    private final Interpolator mZoomInterpolator = new AccelerateDecelerateInterpolator();

    private CustomGestureDetector mScaleDragDetector;
    private GestureDetectorCompat mGestureDetector;

    private int mParentTakeOverPolicy = TAKE_OVER_UNTIL_NEXT_DOWN;
    private boolean mBlockParentIntercept = false;
    private boolean mAllowParentInterceptOnEdge = true;
    private boolean mBlockParentTouchEventInLifeCycle = false;
    private boolean mAllowRotateInAnyScale = true;

    private final Matrix mBaseMatrix = new Matrix();
    private final Matrix mDrawMatrix = new Matrix();
    private final Matrix mSuppMatrix = new Matrix();
    private final RectF mDisplayRect = new RectF();
    private final float[] mMatrixValues = new float[9];

    private int mImageInfoHeight = -1, mImageInfoWidth = -1;
    private FlingRunnable mCurrentFlingRunnable;
    private WeakReference<DraweeView<GenericDraweeHierarchy>> mDraweeView;

    private OnPhotoTapListener mPhotoTapListener;
    private OnViewTapListener mViewTapListener;
    private View.OnLongClickListener mLongClickListener;
    private OnScaleChangeListener mScaleChangeListener;

    private float mBaseRotation;
    private float mDoubleTapScale = -1;
    private float mCompensateScaleValue = DEFAULT_COMPENSATE_SCALE;
    private int mCurrentRotation = DEGREE_0;
    private int mZoomDuration = DEFAULT_ZOOM_DURATION;

    private boolean mEnableDraweeMatrix;

    @SuppressLint("ClickableViewAccessibility")
    public Attacher(DraweeView<GenericDraweeHierarchy> draweeView) {
        mDraweeView = new WeakReference<>(draweeView);
        draweeView.getHierarchy().setActualImageScaleType(ScalingUtils.ScaleType.FIT_CENTER);
        draweeView.setOnTouchListener(this);

        mScaleDragDetector = new CustomGestureDetector(draweeView.getContext(), this);

        mGestureDetector = new GestureDetectorCompat(draweeView.getContext(), new GestureDetector.SimpleOnGestureListener() {

            // forward long click listener
            @Override
            public void onLongPress(MotionEvent e) {
                if (mLongClickListener != null) {
                    DraweeView<GenericDraweeHierarchy> draweeView = getDraweeView();
                    if (draweeView != null) {
                        mLongClickListener.onLongClick(draweeView);
                    }
                }
            }
        });

        mGestureDetector.setOnDoubleTapListener(new GestureDetector.OnDoubleTapListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                DraweeView<GenericDraweeHierarchy> draweeView = getDraweeView();
                if (draweeView == null) {
                    return false;
                }
                if (!mEnableDraweeMatrix) {
                    if (mViewTapListener != null) {
                        mViewTapListener.onViewTap(draweeView, e.getX(), e.getY());
                    }
                    return true;
                }
                final RectF displayRect = getDisplayRect();

                if (displayRect != null) {
                    if (mViewTapListener != null) {
                        mViewTapListener.onViewTap(draweeView, e.getX(), e.getY());
                    }
                    final float x = e.getX(), y = e.getY();

                    if (displayRect.contains(x, y)) {

                        float xResult = (x - displayRect.left)
                                / displayRect.width();
                        float yResult = (y - displayRect.top)
                                / displayRect.height();

                        if (mPhotoTapListener != null) {
                            mPhotoTapListener.onPhotoTap(draweeView, xResult, yResult);
                        }
                        return true;
                    }
                }
                return false;
            }

            @Override
            public boolean onDoubleTap(MotionEvent ev) {
                if (!mEnableDraweeMatrix) {
                    return false;
                }
                try {
                    float scale = getScale();
                    float x = ev.getX();
                    float y = ev.getY();
                    float minScale = getCompensatedMinScale();
                    float doubleTapScale
                            = mDoubleTapScale == -1 ? getCompensatedMaxScale() : mDoubleTapScale;
                    if (scale >= minScale && scale < doubleTapScale) {
                        setScale(doubleTapScale, x, y, true);
                    } else {
                        setScale(minScale, x, y, true);
                    }
                } catch (ArrayIndexOutOfBoundsException e) {
                    // Can sometimes happen when getX() and getY() is called
                }

                return true;
            }

            @Override
            public boolean onDoubleTapEvent(MotionEvent e) {
                // Wait for the confirmed onDoubleTap() instead
                return false;
            }
        });
    }

    public void setOnDoubleTapListener(GestureDetector.OnDoubleTapListener newOnDoubleTapListener) {
        this.mGestureDetector.setOnDoubleTapListener(newOnDoubleTapListener);
    }

    public DraweeView<GenericDraweeHierarchy> getDraweeView() {
        return mDraweeView.get();
    }

    public float getCompensatedMinScale() {
        return ScaleConfig.getMinScale() * mCompensateScaleValue;
    }

    public float getCompensatedMaxScale() {
        return ScaleConfig.getMaxScale() * mCompensateScaleValue;
    }

    public float getCompensateScaleAtLevel(int level) {
        return ScaleConfig.getScaleAtLevel(level) * mCompensateScaleValue;
    }

    public float getScale() {
        return (float) Math.sqrt(
                (float) Math.pow(getMatrixValue(mSuppMatrix, Matrix.MSCALE_X), 2) + (float) Math.pow(
                        getMatrixValue(mSuppMatrix, Matrix.MSKEW_Y), 2));
    }

    public float getCompensateScaleValue() {
        return mCompensateScaleValue;
    }

    public int getCurrentRotation() {
        return mCurrentRotation;
    }

    public void setRotateConfig(int currentRotation, float compensateScaleValue) {
        mCurrentRotation = currentRotation;

        if (currentRotation != DEGREE_X) {
            mCompensateScaleValue = compensateScaleValue;

            if (mScaleChangeListener != null) {
                DraweeView<GenericDraweeHierarchy> draweeView = getDraweeView();
                if (draweeView != null) {
                    mScaleChangeListener.onScaleChange(compensateScaleValue,
                            (draweeView.getRight()) / 2, (draweeView.getBottom()) / 2);
                }
            }
        }
    }

    public int getParentTakeOverPolicy() {
        return mParentTakeOverPolicy;
    }

    public void setParentTakeOverPolicy(int mParentTakeOverPolicy) {
        this.mParentTakeOverPolicy = mParentTakeOverPolicy;
    }

    public void setScale(float scale) {
        if (mEnableDraweeMatrix) {
            setScale(scale, false);
        }
    }

    public void setScale(float scale, boolean animate) {
        if (mEnableDraweeMatrix) {
            DraweeView<GenericDraweeHierarchy> draweeView = getDraweeView();
            if (draweeView != null) {
                setScale(scale, (draweeView.getRight()) / 2, (draweeView.getBottom()) / 2, animate);
            }
        }
    }

    public void setScale(float scale, float focalX, float focalY, boolean animate) {
        if (mEnableDraweeMatrix) {
            DraweeView<GenericDraweeHierarchy> draweeView = getDraweeView();

            if (draweeView == null || scale < getCompensatedMinScale() || scale > getCompensatedMaxScale()) {
                return;
            }

            if (animate) {
                draweeView.post(new AnimatedZoomRunnable(getScale(), scale, focalX, focalY));
            }
            else {
                mSuppMatrix.setScale(scale, scale, focalX, focalY);
                checkMatrixAndInvalidate();
            }
        }
    }

    public void setZoomTransitionDuration(int milliseconds) {
        mZoomDuration = milliseconds;
    }

    public void setAllowParentInterceptOnEdge(boolean allow) {
        mAllowParentInterceptOnEdge = allow;
    }

    public void setAllowRotateInAnyScale(boolean allow) {
        mAllowRotateInAnyScale = allow;
    }

    public boolean isEnableDraweeMatrix() {
        return mEnableDraweeMatrix;
    }

    public void setEnableDraweeMatrix(boolean enableDraweeMatrix) {
        mEnableDraweeMatrix = enableDraweeMatrix;
    }

    private @Nullable
    float[] getDrawableDisplayWidthHeight(DraweeView<GenericDraweeHierarchy> draweeView) {

        Drawable drawable = draweeView.getDrawable();
        if (drawable == null) return null;

        float[] drawableDisplayWidthHeight = new float[2];
        float viewWidth = getViewWidth();
        float viewHeight = getViewHeight();
        if ((mImageInfoWidth / mImageInfoHeight) > (viewWidth / viewHeight)) {
            drawableDisplayWidthHeight[0] = viewWidth;
            drawableDisplayWidthHeight[1] = viewWidth * (mImageInfoHeight / mImageInfoWidth);
        }
        else {
            drawableDisplayWidthHeight[1] = viewHeight;
            drawableDisplayWidthHeight[0] = viewHeight * (mImageInfoWidth * 1.0f / mImageInfoHeight);
        }
        return drawableDisplayWidthHeight;
    }

    public void rotate90Degrees(boolean clockwise, boolean animate) {
        DraweeView<GenericDraweeHierarchy> draweeView = getDraweeView();
        if (draweeView == null || (mImageInfoWidth == -1 && mImageInfoHeight == -1)) return;

        if (mCurrentRotation == DEGREE_X) return;

        final float scale = getScale();
        if (!mAllowRotateInAnyScale && scale > getCompensatedMinScale()) return;

        final float[] drawableDisplayWidthHeight = getDrawableDisplayWidthHeight(draweeView);
        if (drawableDisplayWidthHeight == null) return;

        final float displayWidth = drawableDisplayWidthHeight[0];
        final float displayHeight = drawableDisplayWidthHeight[1];
        final float viewWidth = getViewWidth();
        final float viewHeight = getViewHeight();
        float displaySpec = displayWidth/displayHeight;
        float viewSpec = viewWidth/viewHeight;
        float scaleResetValue = mCompensateScaleValue / scale;
        float scaleValue;
        if (displaySpec > Math.min(viewSpec, 1 / viewSpec)
                && displaySpec < Math.max(viewSpec, 1 / viewSpec)) {
            if (viewSpec < 1)
                scaleValue = displaySpec;
            else
                scaleValue = 1 / displaySpec;
        }
        else if (displaySpec >= Math.max(viewSpec, 1 / viewSpec)) {
            scaleValue = 1 / viewSpec;
        }
        else {
            scaleValue = viewSpec;
        }
        if (mCurrentRotation == DEGREE_90 || mCurrentRotation == DEGREE_270) {
            scaleValue = 1 / scaleValue;
        }
        if (animate) {

            return;
        }

        mSuppMatrix.postScale(scaleResetValue, scaleResetValue);

        mSuppMatrix.postScale(scaleValue, scaleValue);

        if (clockwise)
            mSuppMatrix.postRotate(90);
        else
            mSuppMatrix.postRotate(-90);

        switch (mCurrentRotation) {
            case DEGREE_0:
                setRotateConfig(clockwise ? DEGREE_90 : DEGREE_270, scaleValue);
                break;
            case DEGREE_90:
                setRotateConfig(clockwise ? DEGREE_180 : DEGREE_0, ScaleConfig.SCALE_MIN);
                break;
            case DEGREE_180:
                setRotateConfig(clockwise ? DEGREE_270 : DEGREE_90, scaleValue);
                break;
            case DEGREE_270:
                setRotateConfig(clockwise ? DEGREE_0 : DEGREE_180, ScaleConfig.SCALE_MIN);
                break;
            default:
                break;
        }

        if (checkMatrixBounds()) {
            draweeView.invalidate();
        }
    }

    public void setOnScaleChangeListener(OnScaleChangeListener listener) {
        mScaleChangeListener = listener;
    }

    public void setOnLongClickListener(View.OnLongClickListener listener) {
        mLongClickListener = listener;
    }

    public void setOnPhotoTapListener(OnPhotoTapListener listener) {
        mPhotoTapListener = listener;
    }

    public void setOnViewTapListener(OnViewTapListener listener) {
        mViewTapListener = listener;
    }

    public OnPhotoTapListener getOnPhotoTapListener() {
        return mPhotoTapListener;
    }

    public OnViewTapListener getOnViewTapListener() {
        return mViewTapListener;
    }

    public void update(int imageInfoWidth, int imageInfoHeight) {
        mImageInfoWidth = imageInfoWidth;
        mImageInfoHeight = imageInfoHeight;
        updateBaseMatrix();
    }

    private int getViewWidth() {

        DraweeView<GenericDraweeHierarchy> draweeView = getDraweeView();

        if (draweeView != null) {

            return draweeView.getWidth()
                    - draweeView.getPaddingLeft()
                    - draweeView.getPaddingRight();
        }

        return 0;
    }

    private int getViewHeight() {
        DraweeView<GenericDraweeHierarchy> draweeView = getDraweeView();
        if (draweeView != null) {
            return draweeView.getHeight()
                    - draweeView.getPaddingTop()
                    - draweeView.getPaddingBottom();
        }
        return 0;
    }

    private float getMatrixValue(Matrix matrix, int whichValue) {
        matrix.getValues(mMatrixValues);
        return mMatrixValues[whichValue];
    }

    public void getSuppMatrix(Matrix matrix) {
        matrix.set(mSuppMatrix);
    }

    public Matrix getDrawMatrix() {
        mDrawMatrix.set(mBaseMatrix);
        mDrawMatrix.postConcat(mSuppMatrix);
        return mDrawMatrix;
    }

    public RectF getDisplayRect() {
        checkMatrixBounds();
        return getDisplayRect(getDrawMatrix());
    }

    public void setBaseRotation(final float degrees) {
        mBaseRotation = degrees % 360;
        updateBaseMatrix();
        setRotationBy(mBaseRotation);
        checkMatrixAndInvalidate();
    }

    public void setRotationTo(float degrees) {
        mSuppMatrix.setRotate(degrees % 360);
        checkMatrixAndInvalidate();
    }

    public void setRotationBy(float degrees) {
        mSuppMatrix.postRotate(degrees % 360);
        checkMatrixAndInvalidate();
    }

    public void checkMatrixAndInvalidate() {

        DraweeView<GenericDraweeHierarchy> draweeView = getDraweeView();

        if (draweeView == null) {
            return;
        }

        if (checkMatrixBounds()) {
            draweeView.invalidate();
        }
    }

    public boolean checkMatrixBounds() {
        RectF rect = getDisplayRect(getDrawMatrix());
        if (rect == null) {
            return false;
        }

        final float height = rect.height(), width = rect.width();
        float deltaX = 0, deltaY = 0;
        final int viewHeight = getViewHeight();

        if (height <= viewHeight) {
            deltaY = (viewHeight - height) / 2 - rect.top;
        } else if (rect.top > 0) {
            deltaY = -rect.top;
        } else if (rect.bottom < (float) viewHeight) {
            deltaY = viewHeight - rect.bottom;
        }

        int viewWidth = getViewWidth();
        if (width <= viewWidth) {
            deltaX = (viewWidth - width) / 2 - rect.left;
        } else if (rect.left > 0) {
            deltaX = -rect.left;
        } else if (rect.right < viewWidth) {
            deltaX = viewWidth - rect.right;
        }

        mSuppMatrix.postTranslate(deltaX, deltaY);
        return true;
    }

    public void clearScaleEffect() {
        switch (mCurrentRotation) {
            case DEGREE_X:
                setRotationTo(mBaseRotation);
                if (getScale() != getCompensatedMinScale()) {
                    setScale(getCompensatedMinScale());
                }
                break;
            case DEGREE_0:
                if (getScale() != getCompensatedMinScale()) {
                    setScale(getCompensatedMinScale());
                }
                break;
            case DEGREE_90:
                rotate90Degrees(false, false);
                break;
            case DEGREE_180:
                rotate90Degrees(false, false);
                rotate90Degrees(false, false);
                break;
            case DEGREE_270:
                rotate90Degrees(true, false);
                break;
                default:break;
        }
    }

    private RectF getDisplayRect(Matrix matrix) {
        DraweeView<GenericDraweeHierarchy> draweeView = getDraweeView();
        if (draweeView == null || (mImageInfoWidth == -1 && mImageInfoHeight == -1)) {
            return null;
        }
        mDisplayRect.set(0.0F, 0.0F, mImageInfoWidth, mImageInfoHeight);
        draweeView.getHierarchy().getActualImageBounds(mDisplayRect);
        matrix.mapRect(mDisplayRect);
        return mDisplayRect;
    }

    private void updateBaseMatrix() {
        if (mImageInfoWidth == -1 && mImageInfoHeight == -1) {
            return;
        }
        resetMatrix();
    }

    private void resetMatrix() {
        mBaseMatrix.reset();
        setRotationBy(mBaseRotation);
        checkMatrixBounds();
        DraweeView<GenericDraweeHierarchy> draweeView = getDraweeView();
        if (draweeView != null) {
            draweeView.invalidate();
        }
    }

    private void checkMinScale() {
        DraweeView<GenericDraweeHierarchy> draweeView = getDraweeView();
        if (draweeView == null) {
            return;
        }

        if (getScale() < getCompensatedMinScale()) {
            RectF rect = getDisplayRect();
            if (null != rect) {
                draweeView.post(new AnimatedZoomRunnable(getScale(), getCompensatedMinScale(),
                        rect.centerX(), rect.centerY()));
            }
        }
    }

    @Override public void onScale(float scaleFactor, float focusX, float focusY) {
        if ((getScale() < getCompensatedMaxScale() || scaleFactor < 1f)
                && (getScale() > getCompensatedMinScale() || scaleFactor > 1f)) {

            if (mScaleChangeListener != null) {
                mScaleChangeListener.onScaleChange(scaleFactor, focusX, focusY);
            }

            mSuppMatrix.postScale(scaleFactor, scaleFactor, focusX, focusY);
            checkMatrixAndInvalidate();
        }
    }

    @Override public void onScaleEnd() {
        checkMinScale();
    }

    @Override public void onDrag(float dx, float dy) {
        if (!mScaleDragDetector.isScaling()) {
            mSuppMatrix.postTranslate(dx, dy);
            checkMatrixAndInvalidate();
            parentTakeOverOnEdge(dx, dy, mParentTakeOverPolicy);
        }
    }

    @Override public void onFling(float startX, float startY, float velocityX, float velocityY) {
        DraweeView<GenericDraweeHierarchy> draweeView = getDraweeView();
        if (draweeView == null) {
            return;
        }

        mCurrentFlingRunnable = new FlingRunnable(draweeView.getContext());
        mCurrentFlingRunnable.fling(getViewWidth(), getViewHeight(), (int) velocityX,
                (int) velocityY);
        draweeView.post(mCurrentFlingRunnable);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override public boolean onTouch(final View v, MotionEvent event) {
        if (mEnableDraweeMatrix) {
            int action = event.getActionMasked();
            switch (action) {
                case MotionEvent.ACTION_DOWN: {
                    if (mParentTakeOverPolicy == TAKE_OVER_UNTIL_NEXT_DOWN) {
                        mBlockParentTouchEventInLifeCycle = false;
                    }
                    ViewParent parent = v.getParent();
                    if (parent != null) {
                        parent.requestDisallowInterceptTouchEvent(true);
                    }
                    cancelFling();
                }
                break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                }
                break;
            }
            if (mScaleDragDetector != null) {
                boolean wasScaling = mScaleDragDetector.isScaling();
                boolean wasDragging = mScaleDragDetector.isDragging();

                mScaleDragDetector.onTouchEvent(event);

                boolean noScale = !wasScaling && !mScaleDragDetector.isScaling();
                boolean noDrag = !wasDragging && !mScaleDragDetector.isDragging();
                mBlockParentIntercept = noScale && noDrag;
            }
        }

        if (mGestureDetector != null) {
            mGestureDetector.onTouchEvent(event);
        }

        return true;
    }

    private void parentTakeOverOnEdge(float dx, float dy, int policy) {
        DraweeView<GenericDraweeHierarchy> draweeView = getDraweeView();
        if (draweeView == null) {
            return;
        }

        ViewParent parent = draweeView.getParent();
        if (null == parent)  {
            return;
        }

        if (mAllowParentInterceptOnEdge && !mScaleDragDetector.isScaling() && !mBlockParentIntercept) {
            RectF rectF = getDisplayRect(getDrawMatrix());
            if (null == rectF) {
                return;
            }

            switch (policy) {
                case TAKE_OVER_ON_EDGE:
                    if ((rectF.right < getViewWidth() && dx < -1f)
                            || (rectF.bottom < getViewHeight() && dy < -1f)
                            || (rectF.left > 0 && dx > 1f)
                            || (rectF.top > 0 && dy > 1f)) {
                        parent.requestDisallowInterceptTouchEvent(false);
                    }
                    break;
                case TAKE_OVER_UNTIL_NEXT_DOWN:
                    if ((rectF.right > getViewWidth() && dx < -1f)
                            || (rectF.bottom > getViewHeight() && dy < -1f)
                            || (rectF.left < 0 && dx > 1f)
                            || (rectF.top < 0 && dy > 1f)) {
                        if (!mBlockParentTouchEventInLifeCycle) {
                            mBlockParentTouchEventInLifeCycle = true;
                            parent.requestDisallowInterceptTouchEvent(true);
                        }
                    }
                    else if (!mBlockParentTouchEventInLifeCycle) {
                        parent.requestDisallowInterceptTouchEvent(false);
                    }
                    break;
                default:break;
            }
        } else {
            parent.requestDisallowInterceptTouchEvent(true);
        }
    }

    private class AnimatedZoomRunnable implements Runnable {
        private final float mFocalX, mFocalY;
        private final long mStartTime;
        private final float mZoomStart, mZoomEnd;

        public AnimatedZoomRunnable(final float currentZoom, final float targetZoom,
                                    final float focalX, final float focalY) {
            mFocalX = focalX;
            mFocalY = focalY;
            mStartTime = System.currentTimeMillis();
            mZoomStart = currentZoom;
            mZoomEnd = targetZoom;
        }

        @Override public void run() {

            DraweeView<GenericDraweeHierarchy> draweeView = getDraweeView();
            if (draweeView == null) {
                return;
            }

            float t = interpolate();
            float scale = mZoomStart + t * (mZoomEnd - mZoomStart);
            float deltaScale = scale / getScale();

            onScale(deltaScale, mFocalX, mFocalY);

            if (t < 1f) {
                postOnAnimation(draweeView, this);
            }
        }

        private float interpolate() {
            float t = 1f * (System.currentTimeMillis() - mStartTime) / mZoomDuration;
            t = Math.min(1f, t);
            t = mZoomInterpolator.getInterpolation(t);
            return t;
        }
    }

    private class FlingRunnable implements Runnable {

        private final ScrollerCompat mScroller;
        private int mCurrentX, mCurrentY;

        public FlingRunnable(Context context) {
            mScroller = ScrollerCompat.create(context);
        }

        public void cancelFling() {
            mScroller.abortAnimation();
        }

        public void fling(int viewWidth, int viewHeight, int velocityX, int velocityY) {
            final RectF rect = getDisplayRect();
            if (null == rect) {
                return;
            }

            final int startX = Math.round(-rect.left);
            final int minX, maxX, minY, maxY;

            if (viewWidth < rect.width()) {
                minX = 0;
                maxX = Math.round(rect.width() - viewWidth);
            } else {
                minX = maxX = startX;
            }

            final int startY = Math.round(-rect.top);
            if (viewHeight < rect.height()) {
                minY = 0;
                maxY = Math.round(rect.height() - viewHeight);
            } else {
                minY = maxY = startY;
            }

            mCurrentX = startX;
            mCurrentY = startY;

            if (startX != maxX || startY != maxY) {
                mScroller.fling(startX, startY, velocityX, velocityY, minX, maxX, minY, maxY, 0, 0);
            }
        }

        @Override public void run() {
            if (mScroller.isFinished()) {
                return;
            }

            DraweeView<GenericDraweeHierarchy> draweeView = getDraweeView();

            if (draweeView != null && mScroller.computeScrollOffset()) {
                final int newX = mScroller.getCurrX();
                final int newY = mScroller.getCurrY();
                mSuppMatrix.postTranslate(mCurrentX - newX, mCurrentY - newY);
                draweeView.invalidate();
                mCurrentX = newX;
                mCurrentY = newY;
                postOnAnimation(draweeView, this);
            }
        }
    }

    private void cancelFling() {
        if (mCurrentFlingRunnable != null) {
            mCurrentFlingRunnable.cancelFling();
            mCurrentFlingRunnable = null;
        }
    }

    private void postOnAnimation(View view, Runnable runnable) {
        if (Build.VERSION.SDK_INT >= 16) {
            view.postOnAnimation(runnable);
        } else {
            view.postDelayed(runnable, 16L);
        }
    }

    protected void onDetachedFromWindow() {
        cancelFling();
    }
}