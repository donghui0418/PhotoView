/*
 Copyright 2011, 2012 Chris Banes.
 <p>
 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at
 <p>
 http://www.apache.org/licenses/LICENSE-2.0
 <p>
 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */
package com.github.chrisbanes.photoview;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.Matrix.ScaleToFit;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.util.SparseArray;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnLongClickListener;
import android.view.ViewParent;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.Interpolator;
import android.widget.ImageView;
import android.widget.ImageView.ScaleType;
import android.widget.OverScroller;

import androidx.annotation.FloatRange;
import androidx.annotation.IntDef;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import static com.github.chrisbanes.photoview.ScaleConfig.SCALE_VALUE_MAX;
import static com.github.chrisbanes.photoview.ScaleConfig.SCALE_VALUE_MIN;

/**
 * The component of {@link PhotoView} which does the work allowing for zooming, scaling, panning, etc.
 * It is made public in case you need to subclass something other than AppCompatImageView and still
 * gain the functionality that {@link PhotoView} offers
 */
public class PhotoViewAttacher implements View.OnTouchListener,
    View.OnLayoutChangeListener {

    private static final int DEFAULT_ZOOM_DURATION = 200;
    private static final int DEFAULT_ROTATE_DURATION = 200;

    private static final int PARENT_INTERCEPT = 0;
    private static final int PARENT_INTERCEPT_IN_TOUCH_LIFECYCLE = 1;

    private static final int DEGREE_0 = 0;
    private static final int DEGREE_90 = 90;
    private static final int DEGREE_180 = 180;
    private static final int DEGREE_270 = 270;

    @IntDef({DEGREE_0, DEGREE_90, DEGREE_180, DEGREE_270})
    @Retention(RetentionPolicy.RUNTIME)
    @interface DegreeDefines {}

    private static int SINGLE_TOUCH = 1;

    private Interpolator mZoomInterpolator = new AccelerateDecelerateInterpolator();
    private Interpolator mRotateInterpolator = new AccelerateDecelerateInterpolator();

    private int mZoomDuration = DEFAULT_ZOOM_DURATION;
    private int mRotateDuration = DEFAULT_ROTATE_DURATION;

    private int mEdgeDragPolicy = PARENT_INTERCEPT_IN_TOUCH_LIFECYCLE;

    private boolean mAllowParentInterceptOnEdge = true;
    private boolean mBlockParentIntercept = false;
    private boolean mBlockParentTouchEventInLifeCycle = false;
    private boolean mAllowRotateInAnyScale = true;

    private ImageView mImageView;

    // Gesture Detectors
    private GestureDetector mGestureDetector;
    private CustomGestureDetector mScaleDragDetector;

    // These are set so we don't keep allocating them on the heap
    private final Matrix mBaseMatrix = new Matrix();
    private final Matrix mDrawMatrix = new Matrix();
    private final Matrix mSuppMatrix = new Matrix();
    private final RectF mDisplayRect = new RectF();
    private final float[] mMatrixValues = new float[9];

    // Listeners
    private OnMatrixChangedListener mMatrixChangeListener;
    private OnPhotoTapListener mPhotoTapListener;
    private OnOutsidePhotoTapListener mOutsidePhotoTapListener;
    private OnViewTapListener mViewTapListener;
    private View.OnClickListener mOnClickListener;
    private OnLongClickListener mLongClickListener;
    private OnScaleChangedListener mScaleChangeListener;
    private OnSingleFlingListener mSingleFlingListener;
    private OnViewDragListener mOnViewDragListener;

    private FlingRunnable mCurrentFlingRunnable;
    private float mBaseRotation;

    private float mCompensateScale = 1f;
    private @DegreeDefines int mCurrentDegree = DEGREE_0;

    private boolean mZoomEnabled = true;
    private ScaleType mScaleType = ScaleType.FIT_CENTER;

    private SparseArray<Float> scaleLevels = new SparseArray<>();

    private OnGestureListener onGestureListener = new OnGestureListener() {
        @Override
        public void onDrag(float dx, float dy) {
            if (mScaleDragDetector.isScaling()) {
                return; // Do not drag if we are already scaling
            }
            if (mOnViewDragListener != null) {
                mOnViewDragListener.onDrag(dx, dy);
            }
            mSuppMatrix.postTranslate(dx, dy);
            checkAndDisplayMatrix();
            handleEdgeDrag(dx, dy, mEdgeDragPolicy);
        }

        @Override
        public void onFling(float startX, float startY, float velocityX, float velocityY) {
            mCurrentFlingRunnable = new FlingRunnable(mImageView.getContext());
            mCurrentFlingRunnable.fling(getImageViewWidth(mImageView),
                getImageViewHeight(mImageView), (int) velocityX, (int) velocityY);
            mImageView.post(mCurrentFlingRunnable);
        }

        @Override
        public void onScale(float scaleFactor, float focusX, float focusY) {
            if (getScale() < getCompensatedMaxScale() || scaleFactor < 1f) {
                if (mScaleChangeListener != null) {
                    mScaleChangeListener.onScaleChange(scaleFactor, focusX, focusY);
                }
                mSuppMatrix.postScale(scaleFactor, scaleFactor, focusX, focusY);
                checkAndDisplayMatrix();
            }
        }
    };

    public PhotoViewAttacher(ImageView imageView) {
        mImageView = imageView;
        imageView.setOnTouchListener(this);
        imageView.addOnLayoutChangeListener(this);
        if (imageView.isInEditMode()) {
            return;
        }
        mBaseRotation = 0.0f;
        // Create Gesture Detectors...
        mScaleDragDetector = new CustomGestureDetector(imageView.getContext(), onGestureListener);
        mGestureDetector = new GestureDetector(imageView.getContext(), new GestureDetector.SimpleOnGestureListener() {

            // forward long click listener
            @Override
            public void onLongPress(MotionEvent e) {
                if (mLongClickListener != null) {
                    mLongClickListener.onLongClick(mImageView);
                }
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2,
                float velocityX, float velocityY) {
                if (mSingleFlingListener != null) {
                    if (getScale() > getCompensatedMinScale()) {
                        return false;
                    }
                    if (e1.getPointerCount() > SINGLE_TOUCH
                        || e2.getPointerCount() > SINGLE_TOUCH) {
                        return false;
                    }
                    return mSingleFlingListener.onFling(e1, e2, velocityX, velocityY);
                }
                return false;
            }
        });
        mGestureDetector.setOnDoubleTapListener(new GestureDetector.OnDoubleTapListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (mOnClickListener != null) {
                    mOnClickListener.onClick(mImageView);
                }
                final RectF displayRect = getDisplayRect();
                final float x = e.getX(), y = e.getY();
                if (mViewTapListener != null) {
                    mViewTapListener.onViewTap(mImageView, x, y);
                }
                if (displayRect != null) {
                    // Check to see if the user tapped on the photo
                    if (displayRect.contains(x, y)) {
                        float xResult = (x - displayRect.left)
                            / displayRect.width();
                        float yResult = (y - displayRect.top)
                            / displayRect.height();
                        if (mPhotoTapListener != null) {
                            mPhotoTapListener.onPhotoTap(mImageView, xResult, yResult);
                        }
                        return true;
                    } else {
                        if (mOutsidePhotoTapListener != null) {
                            mOutsidePhotoTapListener.onOutsidePhotoTap(mImageView);
                        }
                    }
                }
                return false;
            }

            @Override
            public boolean onDoubleTap(MotionEvent ev) {
                float x = -1;
                float y = -1;
                try {
                    x = ev.getX();
                    y = ev.getY();
                } catch (ArrayIndexOutOfBoundsException e) {
                    // Can sometimes happen when getX() and getY() is called
                }
                if (x != -1 && y != -1) {
                    float scale = getScale();
                    float minScale = getCompensatedMinScale();
                    float maxScale = getCompensatedMaxScale();
                    if (scale > minScale && scale <= maxScale) {
                        setScale(minScale, x, y, true);
                    } else {
                        setScale(maxScale, x, y, true);
                    }
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

    public void setOnScaleChangeListener(OnScaleChangedListener onScaleChangeListener) {
        this.mScaleChangeListener = onScaleChangeListener;
    }

    public void setOnSingleFlingListener(OnSingleFlingListener onSingleFlingListener) {
        this.mSingleFlingListener = onSingleFlingListener;
    }

    @Deprecated
    public boolean isZoomEnabled() {
        return mZoomEnabled;
    }

    public RectF getDisplayRect() {
        checkMatrixBounds();
        return getDisplayRect(getDrawMatrix());
    }

    public boolean setDisplayMatrix(Matrix finalMatrix) {
        if (finalMatrix == null) {
            throw new IllegalArgumentException("Matrix cannot be null");
        }
        if (mImageView.getDrawable() == null) {
            return false;
        }
        mSuppMatrix.set(finalMatrix);
        checkAndDisplayMatrix();
        return true;
    }

    private void handleEdgeDrag(float dx, float dy, int policy) {
        ViewParent parent = mImageView.getParent();
        if (null == parent) return;
        if (mAllowParentInterceptOnEdge && !mScaleDragDetector.isScaling() && !mBlockParentIntercept) {
            RectF rectF = getDisplayRect(getDrawMatrix());
            if (null == rectF) return;
            switch (policy) {
                case PARENT_INTERCEPT:
                    if ((rectF.right < getImageViewWidth(mImageView) && dx < -1f)
                            || (rectF.bottom < getImageViewHeight(mImageView) && dy < -1f)
                            || (rectF.left > 0 && dx > 1f)
                            || (rectF.top > 0 && dy > 1f)) {
                        parent.requestDisallowInterceptTouchEvent(false);
                    }
                    break;
                case PARENT_INTERCEPT_IN_TOUCH_LIFECYCLE:
                    if ((rectF.right > getImageViewWidth(mImageView) && dx < -1f)
                            || (rectF.bottom > getImageViewHeight(mImageView) && dy < -1f)
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

    public void setBaseRotation(final float degrees) {
        mBaseRotation = degrees % 360;
        update();
        setRotationBy(mBaseRotation);
        checkAndDisplayMatrix();
    }

    public void setRotationTo(float degrees) {
        mSuppMatrix.setRotate(degrees % 360);
        checkAndDisplayMatrix();
    }

    public void setRotationBy(float degrees) {
        mSuppMatrix.postRotate(degrees % 360);
        checkAndDisplayMatrix();
    }

    private float[] getDrawableDisplayWidthHeight() {
        Drawable drawable = mImageView.getDrawable();
        if (drawable == null) {
            return null;
        }
        float[] drawableDisplayWidthHeight = new float[2];
        float viewWidth = getImageViewWidth(mImageView);
        float viewHeight = getImageViewHeight(mImageView);
        float drawableWidth = drawable.getIntrinsicWidth();
        float drawableHeight = drawable.getIntrinsicHeight();
        switch (mScaleType) {
            case CENTER_INSIDE:
                if (drawableWidth <= viewWidth && drawableHeight <= viewHeight) {
                    drawableDisplayWidthHeight[0] = drawableWidth;
                    drawableDisplayWidthHeight[1] = drawableHeight;
                }
                break;
            case FIT_CENTER:
                if ((drawableWidth / drawableHeight) > (viewWidth / viewHeight)) {
                    drawableDisplayWidthHeight[0] = viewWidth;
                    drawableDisplayWidthHeight[1] = viewWidth * (drawableHeight / drawableWidth);
                }
                else {
                    drawableDisplayWidthHeight[1] = viewHeight;
                    drawableDisplayWidthHeight[0] = viewHeight * (drawableWidth / drawableHeight);
                }
                break;
            default:
                drawableDisplayWidthHeight = null;
                break;
        }
        return drawableDisplayWidthHeight;
    }

    public void rotateTo(@DegreeDefines int degree, boolean clockwise, boolean animate) {

        if (mCurrentDegree == degree) {
            return;
        }

        if (mScaleType != ScaleType.FIT_CENTER
                && mScaleType != ScaleType.CENTER_INSIDE) {
            throw new IllegalArgumentException("Scale type must be fit_center or center_inside");
        }

        final float scale = getScale();
        if (!mAllowRotateInAnyScale && scale > getCompensatedMinScale()) return;

        final float[] drawableDisplayWidthHeight = getDrawableDisplayWidthHeight();
        final float displayWidth = drawableDisplayWidthHeight[0];
        final float displayHeight = drawableDisplayWidthHeight[1];
        final float viewWidth = getImageViewWidth(mImageView);
        final float viewHeight = getImageViewHeight(mImageView);
        float displaySpec = displayWidth/displayHeight;
        float viewSpec = viewWidth/viewHeight;
        float scaleResetFactor = mCompensateScale / scale;
        float scaleFactor = 1f;
        // The scaleFactor needs to be calculated
        // only when the angle difference between the front and rear of the rotation is 90 degrees
        // 仅当旋转前后角度相差90度时才需要计算scaleFactor
        if (Math.abs(mCurrentDegree - degree) == DEGREE_90) {
            switch (mScaleType) {
                case CENTER_INSIDE:
                    if (displayWidth <= viewWidth && displayHeight <= viewHeight
                            && Math.max(displayWidth, displayHeight)
                            > Math.min(viewWidth, viewHeight)) {
                        scaleFactor = Math.min(viewWidth, viewHeight)
                                / Math.max(displayWidth, displayHeight);
                    }
                    break;
                case FIT_CENTER:
                    if (displaySpec > Math.min(viewSpec, 1 / viewSpec)
                            && displaySpec < Math.max(viewSpec, 1 / viewSpec)) {
                        if (viewSpec < 1)
                            scaleFactor = displaySpec;
                        else
                            scaleFactor = 1 / displaySpec;
                    } else if (displaySpec >= Math.max(viewSpec, 1 / viewSpec)) {
                        scaleFactor = 1 / viewSpec;
                    } else {
                        scaleFactor = viewSpec;
                    }
                    if (mCurrentDegree == DEGREE_90 || mCurrentDegree == DEGREE_270) {
                        scaleFactor = 1 / scaleFactor;
                    }
                    break;
                default:
                    break;
            }
        }

        float rotateFactor;
        if (clockwise) {
            rotateFactor = Math.abs(mCurrentDegree - degree);
        }
        else {
            if (degree > mCurrentDegree) {
                rotateFactor = -(360 - (degree - mCurrentDegree));
            } else {
                rotateFactor = -(mCurrentDegree -degree);
            }
        }

        if (animate) {
            mImageView.post(new RotateRunnable(rotateFactor, scaleResetFactor, scaleFactor));
        }
        else {
            // cancel scale effect before rotate
            mSuppMatrix.postScale(scaleResetFactor, scaleResetFactor);
            //scale to satisfy scale type after rotate
            mSuppMatrix.postScale(scaleFactor, scaleFactor);

            mSuppMatrix.postRotate(rotateFactor);

            checkAndDisplayMatrix();
        }
        setRotateConfig(degree, scaleFactor);
    }

    public float getCompensateScale() {
        return mCompensateScale;
    }

    public int getCurrentRotation() {
        return mCurrentDegree;
    }

    public float getCompensatedMinScale() {
        return getMinScale() * mCompensateScale;
    }

    public float getCompensatedMaxScale() {
        return getMaxScale() * mCompensateScale;
    }

    public float getCompensateScaleAtLevel(int level) {
        return getScaleAtLevel(level) * mCompensateScale;
    }

    public float getScale() {
        return (float) Math.sqrt((float) Math.pow(getValue(mSuppMatrix, Matrix.MSCALE_X), 2)
                + (float) Math.pow(getValue(mSuppMatrix, Matrix.MSKEW_Y), 2));
    }

    public int getEdgeDragPolicy() {
        return mEdgeDragPolicy;
    }

    public void setEdgeDragPolicy(int mEdgeDragPolicy) {
        this.mEdgeDragPolicy = mEdgeDragPolicy;
    }

    public ScaleType getScaleType() {
        return mScaleType;
    }

    @Override
    public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft, int oldTop, int
        oldRight, int oldBottom) {
        // Update our base matrix, as the bounds have changed
        if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom) {
            updateBaseMatrix(mImageView.getDrawable());
        }
    }

    @Override
    public boolean onTouch(View v, MotionEvent ev) {
        boolean handled = false;
        if (mZoomEnabled && Util.hasDrawable((ImageView) v)) {
            switch (ev.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    if (mEdgeDragPolicy == PARENT_INTERCEPT_IN_TOUCH_LIFECYCLE) {
                        mBlockParentTouchEventInLifeCycle = false;
                    }
                    ViewParent parent = v.getParent();
                    // First, disable the Parent from intercepting the touch
                    // event
                    if (parent != null) {
                        parent.requestDisallowInterceptTouchEvent(true);
                    }
                    // If we're flinging, and the user presses down, cancel
                    // fling
                    cancelFling();
                    break;
                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_UP:
                    // If the user has zoomed less than min scale, zoom back
                    // to min scale
                    if (getScale() < getCompensatedMinScale()) {
                        RectF rect = getDisplayRect();
                        if (rect != null) {
                            v.post(new AnimatedZoomRunnable(getScale(), getCompensatedMinScale(),
                                rect.centerX(), rect.centerY()));
                            handled = true;
                        }
                    } else if (getScale() > getCompensatedMaxScale()) {
                        RectF rect = getDisplayRect();
                        if (rect != null) {
                            v.post(new AnimatedZoomRunnable(getScale(), getCompensatedMaxScale(),
                                rect.centerX(), rect.centerY()));
                            handled = true;
                        }
                    }
                    break;
            }
            // Try the Scale/Drag detector
            if (mScaleDragDetector != null) {
                boolean wasScaling = mScaleDragDetector.isScaling();
                boolean wasDragging = mScaleDragDetector.isDragging();
                handled = mScaleDragDetector.onTouchEvent(ev);
                boolean didntScale = !wasScaling && !mScaleDragDetector.isScaling();
                boolean didntDrag = !wasDragging && !mScaleDragDetector.isDragging();
                mBlockParentIntercept = didntScale && didntDrag;
            }
            // Check to see if the user double tapped
            if (mGestureDetector != null && mGestureDetector.onTouchEvent(ev)) {
                handled = true;
            }

        }
        return handled;
    }

    public void setAllowParentInterceptOnEdge(boolean allow) {
        mAllowParentInterceptOnEdge = allow;
    }

    public void setAllowRotateInAnyScale(boolean allow) {
        mAllowRotateInAnyScale = allow;
    }

    public SparseArray<Float> getScaleLevels() {
        return scaleLevels;
    }

    public float getMinScale() {
        return scaleLevels.get(0);
    }

    public float getMaxScale() {
        return scaleLevels.get(scaleLevels.size() - 1);
    }

    public float getScaleAtLevel(int level) {
        return scaleLevels.get(level);
    }

    public int getLevelByScale(float scale) {
        for (int i = 0; i < scaleLevels.size(); ++i) {
            if (scale >= scaleLevels.get(i)) continue;
            return i - 1;
        }
        return scaleLevels.size() - 1;
    }

    public void setScaleLevels(SparseArray<Float> scaleLevels) {
        if (scaleLevels.size() < 2) {
            throw new IllegalArgumentException("At least two levels are required");
        }
        for (int i = 0; i < scaleLevels.size(); ++i) {
            for (int j = 0; j < i; ++j) {
                if (scaleLevels.get(i) <= scaleLevels.get(j))
                    throw new IllegalArgumentException(String.format("Scale level %d value must " +
                            "bigger than scale level %d value", i, j));
            }
            setScaleLevel(i, scaleLevels.get(i));
        }
    }

    public void setScaleLevels(float... scaleLevels) {
        if (scaleLevels.length < 2) {
            throw new IllegalArgumentException("At least two levels are required");
        }
        for (int i = 0; i < scaleLevels.length; ++i) {
            for (int j = 0; j < i; ++j) {
                if (scaleLevels[i]<= scaleLevels[j])
                    throw new IllegalArgumentException(String.format("Scale level %d value must " +
                            "bigger than scale level %d value", i, j));
            }
            setScaleLevel(i, scaleLevels[i]);
        }
    }

    public void setOnLongClickListener(OnLongClickListener listener) {
        mLongClickListener = listener;
    }

    public void setOnClickListener(View.OnClickListener listener) {
        mOnClickListener = listener;
    }

    public void setOnMatrixChangeListener(OnMatrixChangedListener listener) {
        mMatrixChangeListener = listener;
    }

    public void setOnPhotoTapListener(OnPhotoTapListener listener) {
        mPhotoTapListener = listener;
    }

    public void setOnOutsidePhotoTapListener(OnOutsidePhotoTapListener mOutsidePhotoTapListener) {
        this.mOutsidePhotoTapListener = mOutsidePhotoTapListener;
    }

    public void setOnViewTapListener(OnViewTapListener listener) {
        mViewTapListener = listener;
    }

    public void setOnViewDragListener(OnViewDragListener listener) {
        mOnViewDragListener = listener;
    }

    public void setScale(float scale) {
        setScale(scale, false);
    }

    public void setScale(float scale, boolean animate) {
        setScale(scale,
            (mImageView.getRight()) / 2,
            (mImageView.getBottom()) / 2,
            animate);
    }

    public void setScale(float scale, float focalX, float focalY,
        boolean animate) {
        // Check to see if the scale is within bounds
        if (scale < getCompensatedMinScale() || scale > getCompensatedMaxScale()) {
            throw new IllegalArgumentException("Scale must be within the range of minScale and maxScale");
        }
        if (animate) {
            mImageView.post(new AnimatedZoomRunnable(getScale(), scale,
                focalX, focalY));
        } else {
            mSuppMatrix.setScale(scale, scale, focalX, focalY);
            checkAndDisplayMatrix();
        }
    }

    /**
     * Set the zoom interpolator
     *
     * @param interpolator the zoom interpolator
     */
    public void setZoomInterpolator(Interpolator interpolator) {
        mZoomInterpolator = interpolator;
    }

    public void setRotateInterpolator(Interpolator interpolator) {
        mRotateInterpolator = interpolator;
    }

    public void setScaleType(ScaleType scaleType) {
        if (Util.isSupportedScaleType(scaleType) && scaleType != mScaleType) {
            mScaleType = scaleType;
            update();
        }
    }

    public boolean isZoomable() {
        return mZoomEnabled;
    }

    public void setZoomable(boolean zoomable) {
        mZoomEnabled = zoomable;
        update();
    }

    public void update() {
        if (mZoomEnabled) {
            // Update the base matrix using the current drawable
            updateBaseMatrix(mImageView.getDrawable());
        } else {
            // Reset the Matrix...
            resetMatrix();
        }
    }

    /**
     * Get the display matrix
     *
     * @param matrix target matrix to copy to
     */
    public void getDisplayMatrix(Matrix matrix) {
        matrix.set(getDrawMatrix());
    }

    /**
     * Get the current support matrix
     */
    public void getSuppMatrix(Matrix matrix) {
        matrix.set(mSuppMatrix);
    }

    private Matrix getDrawMatrix() {
        mDrawMatrix.set(mBaseMatrix);
        mDrawMatrix.postConcat(mSuppMatrix);
        return mDrawMatrix;
    }

    public Matrix getImageMatrix() {
        return mDrawMatrix;
    }

    public void setZoomTransitionDuration(int milliseconds) {
        this.mZoomDuration = milliseconds;
    }

    public void setRotateTransitionDuration(int milliseconds) {
        mRotateDuration = milliseconds;
    }

    private void setRotateConfig(@DegreeDefines int currentDegree, float compensateScale) {
        mCurrentDegree = currentDegree;
        mCompensateScale = compensateScale;
    }

    private void setScaleLevel(int key, @FloatRange(from = SCALE_VALUE_MIN, to = SCALE_VALUE_MAX) float scaleLevel) {
        if (scaleLevel < SCALE_VALUE_MIN || scaleLevel > SCALE_VALUE_MAX) {
            throw new IllegalArgumentException(String.format("scaleLevel value must between %f and %f",
                    SCALE_VALUE_MIN, SCALE_VALUE_MAX));
        }
        scaleLevels.put(key, scaleLevel);
    }

    /**
     * Helper method that 'unpacks' a Matrix and returns the required value
     *
     * @param matrix     Matrix to unpack
     * @param whichValue Which value from Matrix.M* to return
     * @return returned value
     */
    private float getValue(Matrix matrix, int whichValue) {
        matrix.getValues(mMatrixValues);
        return mMatrixValues[whichValue];
    }

    /**
     * Resets the Matrix back to FIT_CENTER, and then displays its contents
     */
    private void resetMatrix() {
        mSuppMatrix.reset();
        setRotationBy(mBaseRotation);
        setImageViewMatrix(getDrawMatrix());
        checkMatrixBounds();
    }

    private void setImageViewMatrix(Matrix matrix) {
        mImageView.setImageMatrix(matrix);
        // Call MatrixChangedListener if needed
        if (mMatrixChangeListener != null) {
            RectF displayRect = getDisplayRect(matrix);
            if (displayRect != null) {
                mMatrixChangeListener.onMatrixChanged(displayRect);
            }
        }
    }

    /**
     * Helper method that simply checks the Matrix, and then displays the result
     */
    private void checkAndDisplayMatrix() {
        if (checkMatrixBounds()) {
            setImageViewMatrix(getDrawMatrix());
        }
    }

    /**
     * Helper method that maps the supplied Matrix to the current Drawable
     *
     * @param matrix - Matrix to map Drawable against
     * @return RectF - Displayed Rectangle
     */
    private RectF getDisplayRect(Matrix matrix) {
        Drawable d = mImageView.getDrawable();
        if (d != null) {
            mDisplayRect.set(0, 0, d.getIntrinsicWidth(),
                d.getIntrinsicHeight());
            matrix.mapRect(mDisplayRect);
            return mDisplayRect;
        }
        return null;
    }

    /**
     * Calculate Matrix for FIT_CENTER
     *
     * @param drawable - Drawable being displayed
     */
    private void updateBaseMatrix(Drawable drawable) {
        if (drawable == null) {
            return;
        }
        final float viewWidth = getImageViewWidth(mImageView);
        final float viewHeight = getImageViewHeight(mImageView);
        final int drawableWidth = drawable.getIntrinsicWidth();
        final int drawableHeight = drawable.getIntrinsicHeight();
        mBaseMatrix.reset();
        final float widthScale = viewWidth / drawableWidth;
        final float heightScale = viewHeight / drawableHeight;
        if (mScaleType == ScaleType.CENTER) {
            mBaseMatrix.postTranslate((viewWidth - drawableWidth) / 2F,
                (viewHeight - drawableHeight) / 2F);

        } else if (mScaleType == ScaleType.CENTER_CROP) {
            float scale = Math.max(widthScale, heightScale);
            mBaseMatrix.postScale(scale, scale);
            mBaseMatrix.postTranslate((viewWidth - drawableWidth * scale) / 2F,
                (viewHeight - drawableHeight * scale) / 2F);

        } else if (mScaleType == ScaleType.CENTER_INSIDE) {
            float scale = Math.min(1.0f, Math.min(widthScale, heightScale));
            mBaseMatrix.postScale(scale, scale);
            mBaseMatrix.postTranslate((viewWidth - drawableWidth * scale) / 2F,
                (viewHeight - drawableHeight * scale) / 2F);

        } else {
            RectF mTempSrc = new RectF(0, 0, drawableWidth, drawableHeight);
            RectF mTempDst = new RectF(0, 0, viewWidth, viewHeight);
            if ((int) mBaseRotation % 180 != 0) {
                mTempSrc = new RectF(0, 0, drawableHeight, drawableWidth);
            }
            switch (mScaleType) {
                case FIT_CENTER:
                    mBaseMatrix.setRectToRect(mTempSrc, mTempDst, ScaleToFit.CENTER);
                    break;
                case FIT_START:
                    mBaseMatrix.setRectToRect(mTempSrc, mTempDst, ScaleToFit.START);
                    break;
                case FIT_END:
                    mBaseMatrix.setRectToRect(mTempSrc, mTempDst, ScaleToFit.END);
                    break;
                case FIT_XY:
                    mBaseMatrix.setRectToRect(mTempSrc, mTempDst, ScaleToFit.FILL);
                    break;
                default:
                    break;
            }
        }
        resetMatrix();
    }

    private boolean checkMatrixBounds() {
        final RectF rect = getDisplayRect(getDrawMatrix());
        if (rect == null) {
            return false;
        }
        final float height = rect.height(), width = rect.width();
        float deltaX = 0, deltaY = 0;
        final int viewHeight = getImageViewHeight(mImageView);
        if (height <= viewHeight) {
            switch (mScaleType) {
                case FIT_START:
                    deltaY = -rect.top;
                    break;
                case FIT_END:
                    deltaY = viewHeight - height - rect.top;
                    break;
                default:
                    deltaY = (viewHeight - height) / 2 - rect.top;
                    break;
            }
        } else if (rect.top > 0) {
            deltaY = -rect.top;
        } else if (rect.bottom < viewHeight) {
            deltaY = viewHeight - rect.bottom;
        }
        final int viewWidth = getImageViewWidth(mImageView);
        if (width <= viewWidth) {
            switch (mScaleType) {
                case FIT_START:
                    deltaX = -rect.left;
                    break;
                case FIT_END:
                    deltaX = viewWidth - width - rect.left;
                    break;
                default:
                    deltaX = (viewWidth - width) / 2 - rect.left;
                    break;
            }
        } else if (rect.left > 0) {
            deltaX = -rect.left;
        } else if (rect.right < viewWidth) {
            deltaX = viewWidth - rect.right;
        }
        // Finally actually translate the matrix
        mSuppMatrix.postTranslate(deltaX, deltaY);
        return true;
    }

    private int getImageViewWidth(ImageView imageView) {
        return imageView.getWidth() - imageView.getPaddingLeft() - imageView.getPaddingRight();
    }

    private int getImageViewHeight(ImageView imageView) {
        return imageView.getHeight() - imageView.getPaddingTop() - imageView.getPaddingBottom();
    }

    private void cancelFling() {
        if (mCurrentFlingRunnable != null) {
            mCurrentFlingRunnable.cancelFling();
            mCurrentFlingRunnable = null;
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

        @Override
        public void run() {
            float t = interpolate();
            float scale = mZoomStart + t * (mZoomEnd - mZoomStart);
            float deltaScale = scale / getScale();
            onGestureListener.onScale(deltaScale, mFocalX, mFocalY);
            // We haven't hit our target scale yet, so post ourselves again
            if (t < 1f) {
                Compat.postOnAnimation(mImageView, this);
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

        private final OverScroller mScroller;
        private int mCurrentX, mCurrentY;

        public FlingRunnable(Context context) {
            mScroller = new OverScroller(context);
        }

        public void cancelFling() {
            mScroller.forceFinished(true);
        }

        public void fling(int viewWidth, int viewHeight, int velocityX,
            int velocityY) {
            final RectF rect = getDisplayRect();
            if (rect == null) {
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
            // If we actually can move, fling the scroller
            if (startX != maxX || startY != maxY) {
                mScroller.fling(startX, startY, velocityX, velocityY, minX,
                    maxX, minY, maxY, 0, 0);
            }
        }

        @Override
        public void run() {
            if (mScroller.isFinished()) {
                return; // remaining post that should not be handled
            }
            if (mScroller.computeScrollOffset()) {
                final int newX = mScroller.getCurrX();
                final int newY = mScroller.getCurrY();
                mSuppMatrix.postTranslate(mCurrentX - newX, mCurrentY - newY);
                checkAndDisplayMatrix();
                mCurrentX = newX;
                mCurrentY = newY;
                // Post On animation
                Compat.postOnAnimation(mImageView, this);
            }
        }
    }

    private class RotateRunnable implements Runnable {

        private final float rotation;
        private final float startScale;
        private final float endScale;
        private final float focusX;
        private final float focusY;
        private final long startTime;
        private float rotatedDegrees = 0;
        public RotateRunnable(float rotation, float scaleResetFactor, float scaleFactor) {
            startTime = SystemClock.elapsedRealtime();
            this.rotation = rotation;
            startScale = getScale();
            endScale = scaleResetFactor * scaleFactor * startScale;
            focusX = (mImageView.getRight()) / 2;
            focusY = (mImageView.getBottom()) / 2;
        }

        @Override
        public void run() {
            float t = interpolate();
            float scale = startScale + t * (endScale - startScale);
            float deltaScale = scale / getScale();
            float rotate = t * rotation;
            float deltaRotate = rotate - rotatedDegrees;
            rotatedDegrees += deltaRotate;

            if (mScaleChangeListener != null) {
                mScaleChangeListener.onScaleChange(deltaScale, focusX, focusY);
            }
            mSuppMatrix.postScale(deltaScale, deltaScale, focusX, focusY);
            mSuppMatrix.postRotate(deltaRotate, focusX, focusY);
            checkAndDisplayMatrix();

            if (t < 1f) {
                Compat.postOnAnimation(mImageView, this);
            }
        }

        private float interpolate() {
            float t = 1f * (SystemClock.elapsedRealtime() - startTime) / mRotateDuration;
            t = Math.min(1f, t);
            t = mRotateInterpolator.getInterpolation(t);
            return t;
        }
    }
}
