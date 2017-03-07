package com.nhancv.financechart.lib;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.SystemClock;
import android.support.annotation.Nullable;
import android.support.v4.os.ParcelableCompat;
import android.support.v4.os.ParcelableCompatCreatorCallbacks;
import android.support.v4.view.GestureDetectorCompat;
import android.support.v4.view.ViewCompat;
import android.support.v4.widget.EdgeEffectCompat;
import android.util.AttributeSet;
import android.util.Log;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.widget.OverScroller;

import com.nhancv.financechart.Model;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Created by nhancao on 3/7/17.
 */

public class FinanceChart extends View {
    private static final String TAG = FinanceChart.class.getSimpleName();

    //Initial fling velocity for pan operations, in screen widths (or heights) per second.
    private static final float PAN_VELOCITY_FACTOR = 2f;
    //The scaling factor for a single zoom 'step'.
    private static final float ZOOM_AMOUNT = 0.25f;
    private static final float AXIS_X_MIN = -1f;
    private static final float AXIS_X_MAX = 1f;
    private static final float AXIS_Y_MIN = -1f;
    private static final float AXIS_Y_MAX = 1f;

    private static final int POW10[] = {1, 10, 100, 1000, 10000, 100000, 1000000};

    //Buffers for storing current X and Y stops. See the computeAxisStops method for more details.
    private final AxisStops xStopsBuffer = new AxisStops();
    private final AxisStops yStopsBuffer = new AxisStops();
    private final char[] labelBuffer = new char[100];


    List<Model> modelList = new ArrayList<>();

    private RectF currentViewport = new RectF(AXIS_X_MIN, AXIS_Y_MIN, AXIS_X_MAX, AXIS_Y_MAX);
    /**
     * The current destination rectangle (in pixel coordinates) into which the chart data should
     * be drawn. Chart labels are drawn outside this area.
     */
    private Rect contentRect = new Rect();
    //The scale listener, used for handling multi-finger scale gestures.
    private final ScaleGestureDetector.OnScaleGestureListener scaleGestureListener
            = new ScaleGestureDetector.SimpleOnScaleGestureListener() {
        /**
         * This is the active focal point in terms of the viewport. Could be a local
         * variable but kept here to minimize per-frame allocations.
         */
        private PointF viewportFocus = new PointF();
        private float lastSpanX;
        private float lastSpanY;

        @Override
        public boolean onScaleBegin(ScaleGestureDetector scaleGestureDetector) {
            lastSpanX = ScaleGestureDetectorCompat.getCurrentSpanX(scaleGestureDetector);
            lastSpanY = ScaleGestureDetectorCompat.getCurrentSpanY(scaleGestureDetector);
            return true;
        }

        @Override
        public boolean onScale(ScaleGestureDetector scaleGestureDetector) {
            float spanX = ScaleGestureDetectorCompat.getCurrentSpanX(scaleGestureDetector);
            float spanY = ScaleGestureDetectorCompat.getCurrentSpanY(scaleGestureDetector);

            float newWidth = lastSpanX / spanX * currentViewport.width();
            float newHeight = lastSpanY / spanY * currentViewport.height();

            float focusX = scaleGestureDetector.getFocusX();
            float focusY = scaleGestureDetector.getFocusY();
            hitTest(focusX, focusY, viewportFocus);

            currentViewport.set(
                    viewportFocus.x
                            - newWidth * (focusX - contentRect.left)
                            / contentRect.width(),
                    viewportFocus.y
                            - newHeight * (contentRect.bottom - focusY)
                            / contentRect.height(),
                    0,
                    0);
            currentViewport.right = currentViewport.left + newWidth;
            currentViewport.bottom = currentViewport.top + newHeight;
            constrainViewport();
            ViewCompat.postInvalidateOnAnimation(FinanceChart.this);

            lastSpanX = spanX;
            lastSpanY = spanY;
            return true;
        }
    };
    //Current attribute values and Paints.
    private float labelTextSize;
    private int labelSeparation;
    private int labelTextColor;
    private Paint labelTextPaint;
    private int maxLabelWidth;
    private int labelHeight;
    private float gridThickness;
    private int gridColor;
    private Paint gridPaint;
    private float axisThickness;
    private int axisColor;
    private Paint axisPaint;
    private float dataThickness;
    private int dataColor;
    private Paint dataPaint;
    //State objects and values related to gesture tracking.
    private ScaleGestureDetector scaleGestureDetector;
    private GestureDetectorCompat gestureDetector;
    private OverScroller scroller;
    private Zoomer zoomer;
    private PointF zoomFocalPoint = new PointF();
    private RectF scrollerStartViewport = new RectF(); // Used only for zooms and flings.
    //Edge effect / overscroll tracking objects.
    private EdgeEffectCompat edgeEffectTop;
    private EdgeEffectCompat edgeEffectBottom;
    private EdgeEffectCompat edgeEffectLeft;
    private EdgeEffectCompat edgeEffectRight;
    private boolean edgeEffectTopActive;
    private boolean edgeEffectBottomActive;
    private boolean edgeEffectLeftActive;
    private boolean edgeEffectRightActive;
    //Buffers used during drawing. These are defined as fields to avoid allocation during draw calls.
    private float[] axisXPositionsBuffer = new float[]{};
    private float[] axisYPositionsBuffer = new float[]{};
    private float[] axisXLinesBuffer = new float[]{};
    private float[] axisYLinesBuffer = new float[]{};
    private float[] seriesLinesBuffer = new float[]{};
    private Point surfaceSizeBuffer = new Point();
    //The gesture listener, used for handling simple gestures such as double touches, scrolls, and flings.
    private final GestureDetector.SimpleOnGestureListener gestureListener
            = new GestureDetector.SimpleOnGestureListener() {
        @Override
        public boolean onDown(MotionEvent e) {
            releaseEdgeEffects();
            scrollerStartViewport.set(currentViewport);
            scroller.forceFinished(true);
            ViewCompat.postInvalidateOnAnimation(FinanceChart.this);
            return true;
        }

        @Override
        public boolean onDoubleTap(MotionEvent e) {
            zoomer.forceFinished(true);
            if (hitTest(e.getX(), e.getY(), zoomFocalPoint)) {
                zoomer.startZoom(ZOOM_AMOUNT);
            }
            ViewCompat.postInvalidateOnAnimation(FinanceChart.this);
            return true;
        }

        @Override
        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
            // Scrolling uses math based on the viewport (as opposed to math using pixels).
            /**
             * Pixel offset is the offset in screen pixels, while viewport offset is the
             * offset within the current viewport. For additional information on surface sizes
             * and pixel offsets, see the docs for {@link computeScrollSurfaceSize()}. For
             * additional information about the viewport, see the comments for
             * {@link currentViewport}.
             */
            float viewportOffsetX = distanceX * currentViewport.width() / contentRect.width();
            float viewportOffsetY = -distanceY * currentViewport.height() / contentRect.height();
            computeScrollSurfaceSize(surfaceSizeBuffer);
            int scrolledX = (int) (surfaceSizeBuffer.x
                    * (currentViewport.left + viewportOffsetX - AXIS_X_MIN)
                    / (AXIS_X_MAX - AXIS_X_MIN));
            int scrolledY = (int) (surfaceSizeBuffer.y
                    * (AXIS_Y_MAX - currentViewport.bottom - viewportOffsetY)
                    / (AXIS_Y_MAX - AXIS_Y_MIN));
            boolean canScrollX = currentViewport.left > AXIS_X_MIN
                    || currentViewport.right < AXIS_X_MAX;
            boolean canScrollY = currentViewport.top > AXIS_Y_MIN
                    || currentViewport.bottom < AXIS_Y_MAX;
            setViewportBottomLeft(
                    currentViewport.left + viewportOffsetX,
                    currentViewport.bottom + viewportOffsetY);

            if (canScrollX && scrolledX < 0) {
                edgeEffectLeft.onPull(scrolledX / (float) contentRect.width());
                edgeEffectLeftActive = true;
            }
            if (canScrollY && scrolledY < 0) {
                edgeEffectTop.onPull(scrolledY / (float) contentRect.height());
                edgeEffectTopActive = true;
            }
            if (canScrollX && scrolledX > surfaceSizeBuffer.x - contentRect.width()) {
                edgeEffectRight.onPull((scrolledX - surfaceSizeBuffer.x + contentRect.width())
                        / (float) contentRect.width());
                edgeEffectRightActive = true;
            }
            if (canScrollY && scrolledY > surfaceSizeBuffer.y - contentRect.height()) {
                edgeEffectBottom.onPull((scrolledY - surfaceSizeBuffer.y + contentRect.height())
                        / (float) contentRect.height());
                edgeEffectBottomActive = true;
            }
            return true;
        }

        @Override
        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
            fling((int) -velocityX, (int) -velocityY);
            return true;
        }
    };

    public FinanceChart(Context context) {
        this(context, null, 0);
    }

    public FinanceChart(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public FinanceChart(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    /**
     * Rounds the given number to the given number of significant digits. Based on an answer on
     * <a href="http://stackoverflow.com/questions/202302">Stack Overflow</a>.
     */
    private static float roundToOneSignificantFigure(double num) {
        final float d = (float) Math.ceil((float) Math.log10(num < 0 ? -num : num));
        final int power = 1 - (int) d;
        final float magnitude = (float) Math.pow(10, power);
        final long shifted = Math.round(num * magnitude);
        return shifted / magnitude;
    }

    /**
     * Formats a float value to the given number of decimals. Returns the length of the string.
     * The string begins at out.length - [return value].
     */
    private static int formatFloat(final char[] out, float val, int digits) {
        boolean negative = false;
        if (val == 0) {
            out[out.length - 1] = '0';
            return 1;
        }
        if (val < 0) {
            negative = true;
            val = -val;
        }
        if (digits > POW10.length) {
            digits = POW10.length - 1;
        }
        val *= POW10[digits];
        long lval = Math.round(val);
        int index = out.length - 1;
        int charCount = 0;
        while (lval != 0 || charCount < (digits + 1)) {
            int digit = (int) (lval % 10);
            lval = lval / 10;
            out[index--] = (char) (digit + '0');
            charCount++;
            if (charCount == digits) {
                out[index--] = '.';
                charCount++;
            }
        }
        if (negative) {
            out[index] = '-';
            charCount++;
        }
        return charCount;
    }

    /**
     * Computes the set of axis labels to show given start and stop boundaries and an ideal number
     * of stops between these boundaries.
     *
     * @param start    The minimum extreme (e.g. the left edge) for the axis.
     * @param stop     The maximum extreme (e.g. the right edge) for the axis.
     * @param steps    The ideal number of stops to create. This should be based on available screen
     *                 space; the more space there is, the more stops should be shown.
     * @param outStops The destination {@link AxisStops} object to populate.
     */
    private static void computeAxisStops(float start, float stop, int steps, AxisStops outStops) {
        double range = stop - start;
        if (steps == 0 || range <= 0) {
            outStops.stops = new float[]{};
            outStops.numStops = 0;
            return;
        }

        double rawInterval = range / steps;
        double interval = roundToOneSignificantFigure(rawInterval);
        double intervalMagnitude = Math.pow(10, (int) Math.log10(interval));
        int intervalSigDigit = (int) (interval / intervalMagnitude);
        if (intervalSigDigit > 5) {
            // Use one order of magnitude higher, to avoid intervals like 0.9 or 90
            interval = Math.floor(10 * intervalMagnitude);
        }

        double first = Math.ceil(start / interval) * interval;
        double last = Math.nextUp(Math.floor(stop / interval) * interval);

        double f;
        int i;
        int n = 0;
        for (f = first; f <= last; f += interval) {
            ++n;
        }

        outStops.numStops = n;

        if (outStops.stops.length < n) {
            // Ensure stops contains at least numStops elements.
            outStops.stops = new float[n];
        }

        for (f = first, i = 0; i < n; f += interval, ++i) {
            outStops.stops[i] = (float) f;
        }

        if (interval < 1) {
            outStops.decimals = (int) Math.ceil(-Math.log10(interval));
        } else {
            outStops.decimals = 0;
        }
    }

    private void init() {

        try {
            //Setup properties
            labelTextColor = Color.parseColor("#9ea8b2");
            labelTextSize = convertSpToPixels(12.5f);
            labelSeparation = convertDpToPixels(10);

            gridThickness = convertDpToPixels(1);
            gridColor = Color.parseColor("#79929e");

            axisThickness = convertDpToPixels(1);
            axisColor = Color.parseColor("#22e2fe");

            dataThickness = convertDpToPixels(1);
            dataColor = Color.parseColor("#22e2fe");
        } catch (Exception e) {
            e.printStackTrace();
        }

        //Setup paints
        setupPaints();


        //Setup interactions
        scaleGestureDetector = new ScaleGestureDetector(getContext(), scaleGestureListener);
        gestureDetector = new GestureDetectorCompat(getContext(), gestureListener);

        scroller = new OverScroller(getContext());
        zoomer = new Zoomer(getContext());

        //Setup edge effects
        edgeEffectLeft = new EdgeEffectCompat(getContext());
        edgeEffectTop = new EdgeEffectCompat(getContext());
        edgeEffectRight = new EdgeEffectCompat(getContext());
        edgeEffectBottom = new EdgeEffectCompat(getContext());

        genSampleData();
    }

    private void genSampleData() {
        Random random = new Random();
        modelList = new ArrayList<>();

        int maxYData = Integer.MIN_VALUE;
        for (int i = 0; i <= 20; i++) {
            int value = Math.abs(random.nextInt() % 30);
            maxYData = Math.max(maxYData, value);
            Model model = new Model(String.valueOf(i + 1), String.valueOf(value));
            modelList.add(model);
        }

        for (int i = 0; i < modelList.size(); i++) {
            float value = Float.valueOf(modelList.get(i).getValue());
            float x = (currentViewport.left + (currentViewport.width() / modelList.size() * (i + 1)));
            modelList.get(i).setXVal(x);
            modelList.get(i).setYVal(currentViewport.bottom - value / maxYData * currentViewport.height());
        }

        seriesLinesBuffer = new float[(modelList.size() + 1) * 4];
    }

    private void setupPaints() {
        labelTextPaint = new Paint();
        labelTextPaint.setAntiAlias(true);
        labelTextPaint.setTextSize(labelTextSize);
        labelTextPaint.setColor(labelTextColor);
        labelHeight = (int) Math.abs(labelTextPaint.getFontMetrics().top);
        maxLabelWidth = (int) labelTextPaint.measureText("0000");

        gridPaint = new Paint();
        gridPaint.setStrokeWidth(gridThickness);
        gridPaint.setColor(gridColor);
        gridPaint.setStyle(Paint.Style.STROKE);

        axisPaint = new Paint();
        axisPaint.setStrokeWidth(axisThickness);
        axisPaint.setColor(axisColor);
        axisPaint.setStyle(Paint.Style.STROKE);

        dataPaint = new Paint();
        dataPaint.setStrokeWidth(dataThickness);
        dataPaint.setColor(dataColor);
        dataPaint.setStyle(Paint.Style.STROKE);
        dataPaint.setAntiAlias(true);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        contentRect.set(
                getPaddingLeft() + maxLabelWidth + labelSeparation,
                getPaddingTop(),
                getWidth() - getPaddingRight(),
                getHeight() - getPaddingBottom() - labelHeight - labelSeparation);
        //Init scale
        initScale();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int minChartSize = convertDpToPixels(100);
        setMeasuredDimension(
                Math.max(getSuggestedMinimumWidth(),
                        resolveSize(minChartSize + getPaddingLeft() + maxLabelWidth
                                        + labelSeparation + getPaddingRight(),
                                widthMeasureSpec)),
                Math.max(getSuggestedMinimumHeight(),
                        resolveSize(minChartSize + getPaddingTop() + labelHeight
                                        + labelSeparation + getPaddingBottom(),
                                heightMeasureSpec)));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // Draws axes and text labels
        drawAxes(canvas);

        // Clips the next few drawing operations to the content area
        int clipRestoreCount = canvas.save();
        canvas.clipRect(contentRect);

        drawDataSeriesUnclipped(canvas);
        drawEdgeEffectsUnclipped(canvas);

        // Removes clipping rectangle
        canvas.restoreToCount(clipRestoreCount);

        // Draws chart container
        canvas.drawLine(contentRect.left, contentRect.top, contentRect.left, contentRect.bottom, axisPaint);
        canvas.drawLine(contentRect.left, contentRect.bottom, contentRect.right, contentRect.bottom, axisPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean retVal = scaleGestureDetector.onTouchEvent(event);
        retVal = gestureDetector.onTouchEvent(event) || retVal;
        return retVal || super.onTouchEvent(event);
    }

    @Override
    public void computeScroll() {
        super.computeScroll();

        boolean needsInvalidate = false;

        if (scroller.computeScrollOffset()) {
            // The scroller isn't finished, meaning a fling or programmatic pan operation is
            // currently active.

            computeScrollSurfaceSize(surfaceSizeBuffer);
            int currX = scroller.getCurrX();
            int currY = scroller.getCurrY();

            boolean canScrollX = (currentViewport.left > AXIS_X_MIN
                    || currentViewport.right < AXIS_X_MAX);
            boolean canScrollY = (currentViewport.top > AXIS_Y_MIN
                    || currentViewport.bottom < AXIS_Y_MAX);

            if (canScrollX
                    && currX < 0
                    && edgeEffectLeft.isFinished()
                    && !edgeEffectLeftActive) {
                edgeEffectLeft.onAbsorb((int) OverScrollerCompat.getCurrVelocity(scroller));
                edgeEffectLeftActive = true;
                needsInvalidate = true;
            } else if (canScrollX
                    && currX > (surfaceSizeBuffer.x - contentRect.width())
                    && edgeEffectRight.isFinished()
                    && !edgeEffectRightActive) {
                edgeEffectRight.onAbsorb((int) OverScrollerCompat.getCurrVelocity(scroller));
                edgeEffectRightActive = true;
                needsInvalidate = true;
            }

            if (canScrollY
                    && currY < 0
                    && edgeEffectTop.isFinished()
                    && !edgeEffectTopActive) {
                edgeEffectTop.onAbsorb((int) OverScrollerCompat.getCurrVelocity(scroller));
                edgeEffectTopActive = true;
                needsInvalidate = true;
            } else if (canScrollY
                    && currY > (surfaceSizeBuffer.y - contentRect.height())
                    && edgeEffectBottom.isFinished()
                    && !edgeEffectBottomActive) {
                edgeEffectBottom.onAbsorb((int) OverScrollerCompat.getCurrVelocity(scroller));
                edgeEffectBottomActive = true;
                needsInvalidate = true;
            }

            float currXRange = AXIS_X_MIN + (AXIS_X_MAX - AXIS_X_MIN)
                    * currX / surfaceSizeBuffer.x;
            float currYRange = AXIS_Y_MAX - (AXIS_Y_MAX - AXIS_Y_MIN)
                    * currY / surfaceSizeBuffer.y;
            setViewportBottomLeft(currXRange, currYRange);
        }

        if (zoomer.computeZoom()) {
            // Performs the zoom since a zoom is in progress (either programmatically or via
            // double-touch).
            float newWidth = (1f - zoomer.getCurrZoom()) * scrollerStartViewport.width();
            float newHeight = (1f - zoomer.getCurrZoom()) * scrollerStartViewport.height();
            float pointWithinViewportX = (zoomFocalPoint.x - scrollerStartViewport.left)
                    / scrollerStartViewport.width();
            float pointWithinViewportY = (zoomFocalPoint.y - scrollerStartViewport.top)
                    / scrollerStartViewport.height();
            currentViewport.set(
                    zoomFocalPoint.x - newWidth * pointWithinViewportX,
                    zoomFocalPoint.y - newHeight * pointWithinViewportY,
                    zoomFocalPoint.x + newWidth * (1 - pointWithinViewportX),
                    zoomFocalPoint.y + newHeight * (1 - pointWithinViewportY));
            constrainViewport();
            needsInvalidate = true;
        }

        if (needsInvalidate) {
            ViewCompat.postInvalidateOnAnimation(this);
        }
    }

    @Override
    public Parcelable onSaveInstanceState() {
        Parcelable superState = super.onSaveInstanceState();
        SavedState ss = new SavedState(superState);
        ss.viewport = currentViewport;
        return ss;
    }

    @Override
    public void onRestoreInstanceState(Parcelable state) {
        if (!(state instanceof SavedState)) {
            super.onRestoreInstanceState(state);
            return;
        }

        SavedState ss = (SavedState) state;
        super.onRestoreInstanceState(ss.getSuperState());

        currentViewport = ss.viewport;
    }

    private void initScale() {
        int numBlockOffset = contentRect.width() / convertDpToPixels(60);
        Log.e(TAG, "initScale: " + numBlockOffset);
        currentViewport.set(currentViewport.left, currentViewport.top,
                modelList.get(numBlockOffset).getXVal(), currentViewport.bottom);

        ViewCompat.postInvalidateOnAnimation(this);

    }

    /**
     * Draws the chart axes and labels onto the canvas.
     */
    private void drawAxes(Canvas canvas) {
        // Computes axis stops (in terms of numerical value and position on screen)
        int i;

        computeAxisStops(
                currentViewport.left,
                currentViewport.right,
                contentRect.width() / maxLabelWidth / 2,
                xStopsBuffer);
        computeAxisStops(
                currentViewport.top,
                currentViewport.bottom,
                contentRect.height() / labelHeight / 2,
                yStopsBuffer);

        // Avoid unnecessary allocations during drawing. Re-use allocated
        // arrays and only reallocate if the number of stops grows.
        if (axisXPositionsBuffer.length < xStopsBuffer.numStops) {
            axisXPositionsBuffer = new float[xStopsBuffer.numStops];
        }
        if (axisYPositionsBuffer.length < yStopsBuffer.numStops) {
            axisYPositionsBuffer = new float[yStopsBuffer.numStops];
        }
        if (axisXLinesBuffer.length < xStopsBuffer.numStops * 4) {
            axisXLinesBuffer = new float[xStopsBuffer.numStops * 4];
        }
        if (axisYLinesBuffer.length < yStopsBuffer.numStops * 4) {
            axisYLinesBuffer = new float[yStopsBuffer.numStops * 4];
        }

        // Compute positions
        for (i = 0; i < xStopsBuffer.numStops; i++) {
            axisXPositionsBuffer[i] = getDrawX(xStopsBuffer.stops[i]);
        }
        for (i = 0; i < yStopsBuffer.numStops; i++) {
            axisYPositionsBuffer[i] = getDrawY(yStopsBuffer.stops[i]);
        }

        // Draws grid lines using drawLines (faster than individual drawLine calls)
        for (i = 0; i < xStopsBuffer.numStops; i++) {
            axisXLinesBuffer[i * 4 + 0] = (float) Math.floor(axisXPositionsBuffer[i]);
            axisXLinesBuffer[i * 4 + 1] = contentRect.top;
            axisXLinesBuffer[i * 4 + 2] = (float) Math.floor(axisXPositionsBuffer[i]);
            axisXLinesBuffer[i * 4 + 3] = contentRect.bottom;
        }
        canvas.drawLines(axisXLinesBuffer, 0, xStopsBuffer.numStops * 4, gridPaint);

        for (i = 0; i < yStopsBuffer.numStops; i++) {
            axisYLinesBuffer[i * 4 + 0] = contentRect.left;
            axisYLinesBuffer[i * 4 + 1] = (float) Math.floor(axisYPositionsBuffer[i]);
            axisYLinesBuffer[i * 4 + 2] = contentRect.right;
            axisYLinesBuffer[i * 4 + 3] = (float) Math.floor(axisYPositionsBuffer[i]);
        }
        canvas.drawLines(axisYLinesBuffer, 0, yStopsBuffer.numStops * 4, gridPaint);

        // Draws X labels
        int labelOffset;
        int labelLength;
        labelTextPaint.setTextAlign(Paint.Align.CENTER);
        for (i = 0; i < xStopsBuffer.numStops; i++) {
            // Do not use String.format in high-performance code such as onDraw code.
            labelLength = formatFloat(labelBuffer, xStopsBuffer.stops[i], xStopsBuffer.decimals);
            labelOffset = labelBuffer.length - labelLength;
            canvas.drawText(
                    labelBuffer, labelOffset, labelLength,
                    axisXPositionsBuffer[i],
                    contentRect.bottom + labelHeight + labelSeparation,
                    labelTextPaint);
        }

        // Draws Y labels
        labelTextPaint.setTextAlign(Paint.Align.RIGHT);
        for (i = 0; i < yStopsBuffer.numStops; i++) {
            // Do not use String.format in high-performance code such as onDraw code.
            labelLength = formatFloat(labelBuffer, yStopsBuffer.stops[i], yStopsBuffer.decimals);
            labelOffset = labelBuffer.length - labelLength;
            canvas.drawText(
                    labelBuffer, labelOffset, labelLength,
                    contentRect.left - labelSeparation,
                    axisYPositionsBuffer[i] + labelHeight / 2,
                    labelTextPaint);
        }
    }

    /**
     * Computes the pixel offset for the given X chart value. This may be outside the view bounds.
     */
    private float getDrawX(float x) {
        return contentRect.left
                + contentRect.width()
                * (x - currentViewport.left) / currentViewport.width();
    }

    /**
     * Computes the pixel offset for the given Y chart value. This may be outside the view bounds.
     */
    private float getDrawY(float y) {
        return contentRect.bottom
                - contentRect.height()
                * (y - currentViewport.top) / currentViewport.height();
    }

    /**
     * This method does not clip its drawing, so users should call {@link Canvas#clipRect
     * before calling this method.
     */
    private void drawDataSeriesUnclipped(Canvas canvas) {
        seriesLinesBuffer[0] = contentRect.left;
        seriesLinesBuffer[1] = getDrawY(0);
        seriesLinesBuffer[2] = seriesLinesBuffer[0];
        seriesLinesBuffer[3] = seriesLinesBuffer[1];

        for (int i = 1; i <= modelList.size(); i++) {
            seriesLinesBuffer[i * 4 + 0] = seriesLinesBuffer[(i - 1) * 4 + 2];
            seriesLinesBuffer[i * 4 + 1] = seriesLinesBuffer[(i - 1) * 4 + 3];

            seriesLinesBuffer[i * 4 + 2] = getDrawX(modelList.get(i - 1).getXVal());
            seriesLinesBuffer[i * 4 + 3] = getDrawY(modelList.get(i - 1).getYVal());
        }
        canvas.drawLines(seriesLinesBuffer, dataPaint);
    }

    /**
     * Draws the overscroll "glow" at the four edges of the chart region, if necessary. The edges
     * of the chart region are stored in {@link #contentRect}.
     *
     * @see EdgeEffectCompat
     */
    private void drawEdgeEffectsUnclipped(Canvas canvas) {
        // The methods below rotate and translate the canvas as needed before drawing the glow,
        // since EdgeEffectCompat always draws a top-glow at 0,0.

        boolean needsInvalidate = false;

        if (!edgeEffectTop.isFinished()) {
            final int restoreCount = canvas.save();
            canvas.translate(contentRect.left, contentRect.top);
            edgeEffectTop.setSize(contentRect.width(), contentRect.height());
            if (edgeEffectTop.draw(canvas)) {
                needsInvalidate = true;
            }
            canvas.restoreToCount(restoreCount);
        }

        if (!edgeEffectBottom.isFinished()) {
            final int restoreCount = canvas.save();
            canvas.translate(2 * contentRect.left - contentRect.right, contentRect.bottom);
            canvas.rotate(180, contentRect.width(), 0);
            edgeEffectBottom.setSize(contentRect.width(), contentRect.height());
            if (edgeEffectBottom.draw(canvas)) {
                needsInvalidate = true;
            }
            canvas.restoreToCount(restoreCount);
        }

        if (!edgeEffectLeft.isFinished()) {
            final int restoreCount = canvas.save();
            canvas.translate(contentRect.left, contentRect.bottom);
            canvas.rotate(-90, 0, 0);
            edgeEffectLeft.setSize(contentRect.height(), contentRect.width());
            if (edgeEffectLeft.draw(canvas)) {
                needsInvalidate = true;
            }
            canvas.restoreToCount(restoreCount);
        }

        if (!edgeEffectRight.isFinished()) {
            final int restoreCount = canvas.save();
            canvas.translate(contentRect.right, contentRect.top);
            canvas.rotate(90, 0, 0);
            edgeEffectRight.setSize(contentRect.height(), contentRect.width());
            if (edgeEffectRight.draw(canvas)) {
                needsInvalidate = true;
            }
            canvas.restoreToCount(restoreCount);
        }

        if (needsInvalidate) {
            ViewCompat.postInvalidateOnAnimation(this);
        }
    }

    /**
     * Finds the chart point (i.e. within the chart's domain and range) represented by the
     * given pixel coordinates, if that pixel is within the chart region described by
     * {@link #contentRect}. If the point is found, the "dest" argument is set to the point and
     * this function returns true. Otherwise, this function returns false and "dest" is unchanged.
     */
    private boolean hitTest(float x, float y, PointF dest) {
        if (!contentRect.contains((int) x, (int) y)) {
            return false;
        }

        dest.set(
                currentViewport.left
                        + currentViewport.width()
                        * (x - contentRect.left) / contentRect.width(),
                currentViewport.top
                        + currentViewport.height()
                        * (y - contentRect.bottom) / -contentRect.height());
        return true;
    }

    /**
     * Ensures that current viewport is inside the viewport extremes defined by {@link #AXIS_X_MIN},
     * {@link #AXIS_X_MAX}, {@link #AXIS_Y_MIN} and {@link #AXIS_Y_MAX}.
     */
    private void constrainViewport() {
        currentViewport.left = Math.max(AXIS_X_MIN, currentViewport.left);
        currentViewport.top = Math.max(AXIS_Y_MIN, currentViewport.top);
        currentViewport.bottom = Math.max(Math.nextUp(currentViewport.top),
                Math.min(AXIS_Y_MAX, currentViewport.bottom));
        currentViewport.right = Math.max(Math.nextUp(currentViewport.left),
                Math.min(AXIS_X_MAX, currentViewport.right));
    }

    private void releaseEdgeEffects() {
        edgeEffectLeftActive
                = edgeEffectTopActive
                = edgeEffectRightActive
                = edgeEffectBottomActive
                = false;
        edgeEffectLeft.onRelease();
        edgeEffectTop.onRelease();
        edgeEffectRight.onRelease();
        edgeEffectBottom.onRelease();
    }

    private void fling(int velocityX, int velocityY) {
        releaseEdgeEffects();
        // Flings use math in pixels (as opposed to math based on the viewport).
        computeScrollSurfaceSize(surfaceSizeBuffer);
        scrollerStartViewport.set(currentViewport);
        int startX = (int) (surfaceSizeBuffer.x * (scrollerStartViewport.left - AXIS_X_MIN) / (
                AXIS_X_MAX - AXIS_X_MIN));
        int startY = (int) (surfaceSizeBuffer.y * (AXIS_Y_MAX - scrollerStartViewport.bottom) / (
                AXIS_Y_MAX - AXIS_Y_MIN));
        scroller.forceFinished(true);
        scroller.fling(
                startX,
                startY,
                velocityX,
                velocityY,
                0, surfaceSizeBuffer.x - contentRect.width(),
                0, surfaceSizeBuffer.y - contentRect.height(),
                contentRect.width() / 2,
                contentRect.height() / 2);
        ViewCompat.postInvalidateOnAnimation(this);
    }

    /**
     * Computes the current scrollable surface size, in pixels. For example, if the entire chart
     * area is visible, this is simply the current size of {@link #contentRect}. If the chart
     * is zoomed in 200% in both directions, the returned size will be twice as large horizontally
     * and vertically.
     */
    private void computeScrollSurfaceSize(Point out) {
        out.set(
                (int) (contentRect.width() * (AXIS_X_MAX - AXIS_X_MIN)
                        / currentViewport.width()),
                (int) (contentRect.height() * (AXIS_Y_MAX - AXIS_Y_MIN)
                        / currentViewport.height()));
    }

    /**
     * Sets the current viewport (defined by {@link #currentViewport}) to the given
     * X and Y positions. Note that the Y value represents the topmost pixel position, and thus
     * the bottom of the {@link #currentViewport} rectangle. For more details on why top and
     * bottom are flipped, see {@link #currentViewport}.
     */
    private void setViewportBottomLeft(float x, float y) {
        /**
         * Constrains within the scroll range. The scroll range is simply the viewport extremes
         * (AXIS_X_MAX, etc.) minus the viewport size. For example, if the extrema were 0 and 10,
         * and the viewport size was 2, the scroll range would be 0 to 8.
         */

        float curWidth = currentViewport.width();
        float curHeight = currentViewport.height();
        x = Math.max(AXIS_X_MIN, Math.min(x, AXIS_X_MAX - curWidth));
        y = Math.max(AXIS_Y_MIN + curHeight, Math.min(y, AXIS_Y_MAX));

        currentViewport.set(x, y - curHeight, x + curWidth, y);
        ViewCompat.postInvalidateOnAnimation(this);
    }

    private int convertSpToPixels(float sp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, getResources().getDisplayMetrics());
    }

    private int convertDpToPixels(float dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }


    /**
     * Returns the current viewport (visible extremes for the chart domain and range.)
     */
    public RectF getCurrentViewport() {
        return new RectF(currentViewport);
    }

    /**
     * Sets the chart's current viewport.
     *
     * @see #getCurrentViewport()
     */
    public void setCurrentViewport(RectF viewport) {
        currentViewport = viewport;
        constrainViewport();
        ViewCompat.postInvalidateOnAnimation(this);
    }

    /**
     * Smoothly zooms the chart in one step.
     */
    public void zoomIn() {
        scrollerStartViewport.set(currentViewport);
        zoomer.forceFinished(true);
        zoomer.startZoom(ZOOM_AMOUNT);
        zoomFocalPoint.set(
                (currentViewport.right + currentViewport.left) / 2,
                (currentViewport.bottom + currentViewport.top) / 2);
        ViewCompat.postInvalidateOnAnimation(this);
    }

    /**
     * Smoothly zooms the chart out one step.
     */
    public void zoomOut() {
        scrollerStartViewport.set(currentViewport);
        zoomer.forceFinished(true);
        zoomer.startZoom(-ZOOM_AMOUNT);
        zoomFocalPoint.set(
                (currentViewport.right + currentViewport.left) / 2,
                (currentViewport.bottom + currentViewport.top) / 2);
        ViewCompat.postInvalidateOnAnimation(this);
    }

    /**
     * Smoothly pans the chart left one step.
     */
    public void panLeft() {
        fling((int) (-PAN_VELOCITY_FACTOR * getWidth()), 0);
    }

    /**
     * Smoothly pans the chart right one step.
     */
    public void panRight() {
        fling((int) (PAN_VELOCITY_FACTOR * getWidth()), 0);
    }

    /**
     * Smoothly pans the chart up one step.
     */
    public void panUp() {
        fling(0, (int) (-PAN_VELOCITY_FACTOR * getHeight()));
    }

    /**
     * Smoothly pans the chart down one step.
     */
    public void panDown() {
        fling(0, (int) (PAN_VELOCITY_FACTOR * getHeight()));
    }

    ////////////////////////////////////////////////////////////////////////////////////////////////
    //
    //     Methods related to custom attributes
    //
    ////////////////////////////////////////////////////////////////////////////////////////////////

    public float getLabelTextSize() {
        return labelTextSize;
    }

    public void setLabelTextSize(float labelTextSize) {
        this.labelTextSize = labelTextSize;
        setupPaints();
        ViewCompat.postInvalidateOnAnimation(this);
    }

    public int getLabelTextColor() {
        return labelTextColor;
    }

    public void setLabelTextColor(int labelTextColor) {
        this.labelTextColor = labelTextColor;
        setupPaints();
        ViewCompat.postInvalidateOnAnimation(this);
    }

    public float getGridThickness() {
        return gridThickness;
    }

    public void setGridThickness(float gridThickness) {
        this.gridThickness = gridThickness;
        setupPaints();
        ViewCompat.postInvalidateOnAnimation(this);
    }

    public int getGridColor() {
        return gridColor;
    }

    public void setGridColor(int gridColor) {
        this.gridColor = gridColor;
        setupPaints();
        ViewCompat.postInvalidateOnAnimation(this);
    }

    public float getAxisThickness() {
        return axisThickness;
    }

    public void setAxisThickness(float axisThickness) {
        this.axisThickness = axisThickness;
        setupPaints();
        ViewCompat.postInvalidateOnAnimation(this);
    }

    public int getAxisColor() {
        return axisColor;
    }

    public void setAxisColor(int axisColor) {
        this.axisColor = axisColor;
        setupPaints();
        ViewCompat.postInvalidateOnAnimation(this);
    }

    public float getDataThickness() {
        return dataThickness;
    }

    public void setDataThickness(float dataThickness) {
        this.dataThickness = dataThickness;
    }

    public int getDataColor() {
        return dataColor;
    }

    public void setDataColor(int dataColor) {
        this.dataColor = dataColor;
    }

    /**
     * Persistent state that is saved.
     */
    public static class SavedState extends BaseSavedState {
        public static final Parcelable.Creator<SavedState> CREATOR
                = ParcelableCompat.newCreator(new ParcelableCompatCreatorCallbacks<SavedState>() {
            @Override
            public SavedState createFromParcel(Parcel in, ClassLoader loader) {
                return new SavedState(in);
            }

            @Override
            public SavedState[] newArray(int size) {
                return new SavedState[size];
            }
        });
        private RectF viewport;

        public SavedState(Parcelable superState) {
            super(superState);
        }

        SavedState(Parcel in) {
            super(in);
            viewport = new RectF(in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat());
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            super.writeToParcel(out, flags);
            out.writeFloat(viewport.left);
            out.writeFloat(viewport.top);
            out.writeFloat(viewport.right);
            out.writeFloat(viewport.bottom);
        }

        @Override
        public String toString() {
            return "SavedState{"
                    + Integer.toHexString(System.identityHashCode(this))
                    + " viewport=" + viewport.toString() + "}";
        }
    }

    /**
     * A simple class representing axis label values.
     */
    private static class AxisStops {
        float[] stops = new float[]{};
        int numStops;
        int decimals;
    }
}


/**
 * A simple class that animates double-touch zoom gestures. Functionally similar to a {@link
 * android.widget.Scroller}.
 */
class Zoomer {
    /**
     * The interpolator, used for making zooms animate 'naturally.'
     */
    private Interpolator interpolator;

    /**
     * The total animation duration for a zoom.
     */
    private int animationDurationMillis;

    /**
     * Whether or not the current zoom has finished.
     */
    private boolean finished = true;

    /**
     * The current zoom value; computed by {@link #computeZoom()}.
     */
    private float currentZoom;

    /**
     * The time the zoom started, computed using {@link android.os.SystemClock#elapsedRealtime()}.
     */
    private long startRTC;

    /**
     * The destination zoom factor.
     */
    private float endZoom;

    public Zoomer(Context context) {
        interpolator = new DecelerateInterpolator();
        animationDurationMillis = context.getResources().getInteger(
                android.R.integer.config_shortAnimTime);
    }

    /**
     * Forces the zoom finished state to the given value. Unlike {@link #abortAnimation()}, the
     * current zoom value isn't set to the ending value.
     *
     * @see android.widget.Scroller#forceFinished(boolean)
     */
    public void forceFinished(boolean finished) {
        this.finished = finished;
    }

    /**
     * Aborts the animation, setting the current zoom value to the ending value.
     *
     * @see android.widget.Scroller#abortAnimation()
     */
    public void abortAnimation() {
        finished = true;
        currentZoom = endZoom;
    }

    /**
     * Starts a zoom from 1.0 to (1.0 + endZoom). That is, to zoom from 100% to 125%, endZoom should
     * by 0.25f.
     *
     * @see android.widget.Scroller#startScroll(int, int, int, int)
     */
    public void startZoom(float endZoom) {
        startRTC = SystemClock.elapsedRealtime();
        this.endZoom = endZoom;

        finished = false;
        currentZoom = 1f;
    }

    /**
     * Computes the current zoom level, returning true if the zoom is still active and false if the
     * zoom has finished.
     *
     * @see android.widget.Scroller#computeScrollOffset()
     */
    public boolean computeZoom() {
        if (finished) {
            return false;
        }

        long tRTC = SystemClock.elapsedRealtime() - startRTC;
        if (tRTC >= animationDurationMillis) {
            finished = true;
            currentZoom = endZoom;
            return false;
        }

        float t = tRTC * 1f / animationDurationMillis;
        currentZoom = endZoom * interpolator.getInterpolation(t);
        return true;
    }

    /**
     * Returns the current zoom level.
     *
     * @see android.widget.Scroller#getCurrX()
     */
    public float getCurrZoom() {
        return currentZoom;
    }
}


/**
 * A utility class for using {@link android.widget.OverScroller} in a backward-compatible fashion.
 */
class OverScrollerCompat {
    /**
     * Disallow instantiation.
     */
    private OverScrollerCompat() {
    }

    /**
     * @see android.view.ScaleGestureDetector#getCurrentSpanY()
     */
    @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
    public static float getCurrVelocity(OverScroller overScroller) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
            return overScroller.getCurrVelocity();
        } else {
            return 0;
        }
    }
}


/**
 * A utility class for using {@link android.view.ScaleGestureDetector} in a backward-compatible
 * fashion.
 */
class ScaleGestureDetectorCompat {
    /**
     * Disallow instantiation.
     */
    private ScaleGestureDetectorCompat() {
    }

    /**
     * @see android.view.ScaleGestureDetector#getCurrentSpanX()
     */
    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    public static float getCurrentSpanX(ScaleGestureDetector scaleGestureDetector) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            return scaleGestureDetector.getCurrentSpanX();
        } else {
            return scaleGestureDetector.getCurrentSpan();
        }
    }

    /**
     * @see android.view.ScaleGestureDetector#getCurrentSpanY()
     */
    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    public static float getCurrentSpanY(ScaleGestureDetector scaleGestureDetector) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            return scaleGestureDetector.getCurrentSpanY();
        } else {
            return scaleGestureDetector.getCurrentSpan();
        }
    }
}





