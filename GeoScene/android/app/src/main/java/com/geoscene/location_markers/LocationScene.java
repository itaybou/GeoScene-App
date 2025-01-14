package com.geoscene.location_markers;

import android.app.Activity;
import android.location.Location;
import android.os.Handler;
import android.util.Log;

import com.geoscene.sensors.DeviceSensors;
import com.google.ar.core.Anchor;
import com.google.ar.core.Frame;
import com.google.ar.core.Plane;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.sceneform.ArSceneView;
import com.google.ar.sceneform.Node;
import com.google.ar.sceneform.collision.CollisionShape;
import com.google.ar.sceneform.math.Vector3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.geoscene.sensors.DeviceLocationChanged;
import com.geoscene.geography.LocationUtils;

public class LocationScene {

    private final int distanceGroupSize;
    private String TAG = "LocationScene";

    private final int CALIBRATION_ITERATIONS = 3;
    private final float RENDER_DISTANCE = 10f;
    private final int DEFAULT_DISTANCE_LIMIT = 5000;
    public ArSceneView mArSceneView;
    public Activity context;
    public DeviceSensors sensors;
    public ArrayList<LocationMarker> mLocationMarkers = new ArrayList<>();
    // Anchors are currently re-drawn on an interval. There are likely better
    // ways of doing this, however it's sufficient for now.
    private int anchorRefreshInterval = 1000 * 2; // 2 seconds
    // Limit of where to draw markers within AR scene.
    // They will auto scale, but this helps prevents rendering issues
    private int distanceLimit = 5000;
    private boolean offsetOverlapping = false;
    private boolean removeOverlapping = false;
    // Bearing adjustment. Can be set to calibrate with true north
    private int bearingAdjustment = 0;
    private boolean anchorsNeedRefresh = true;
    private boolean minimalRefreshing = false;
    private boolean refreshing = false;
    private boolean markersRefresh = true;
    private int iteration;
    private int currentDistanceGroup;
    private boolean refreshAnchorsAsLocationChanges = false;
    private Handler mHandler = new Handler();
    Runnable anchorRefreshTask = new Runnable() {
        @Override
        public void run() {
            anchorsNeedRefresh = true;
            if (!markersRefresh) {
                if(iteration < CALIBRATION_ITERATIONS)
                    iteration++;
                setAnchorRefreshInterval(iteration >= CALIBRATION_ITERATIONS ? anchorRefreshInterval * 10 : anchorRefreshInterval);
            }
            mHandler.postDelayed(anchorRefreshTask, anchorRefreshInterval);
        }
    };
    private boolean debugEnabled = false;
    private Session mSession;

    private DeviceLocationChanged locationChangedEvent;

    public LocationScene(Activity context, ArSceneView mArSceneView, DeviceSensors sensors, boolean markersRefresh, int distanceGroupSize) {
        this.context = context;
        this.mSession = mArSceneView.getSession();
        this.mArSceneView = mArSceneView;
        this.markersRefresh = markersRefresh;
        this.currentDistanceGroup = 0;
        this.iteration = 0;
        this.sensors = sensors;
        this.distanceGroupSize = distanceGroupSize;

    }

    public void start() {
        startCalculationTask();
        this.sensors.setLocationEvent(() -> {
            if (locationChangedEvent != null) {
                locationChangedEvent.onChange(sensors.getDeviceLocation());
            }

            if (refreshAnchorsAsLocationChanges) {
                refreshAnchors();
            }
        });
    }

    public boolean isDebugEnabled() {
        return debugEnabled;
    }

    public void setDebugEnabled(boolean debugEnabled) {
        this.debugEnabled = debugEnabled;
    }

    public boolean minimalRefreshing() {
        return minimalRefreshing;
    }

    public void setMinimalRefreshing(boolean minimalRefreshing) {
        this.minimalRefreshing = minimalRefreshing;
    }

    public void setRefreshAnchorsAsLocationChanges(boolean refreshAnchorsAsLocationChanges) {
        if (refreshAnchorsAsLocationChanges) {
            stopCalculationTask();
        } else {
            startCalculationTask();
        }
        refreshAnchors();
        this.refreshAnchorsAsLocationChanges = refreshAnchorsAsLocationChanges;
    }

    /**
     * Get additional event to run as device location changes.
     * Save creating extra sensor classes
     *
     * @return
     */

    /**
     * Set additional event to run as device location changes.
     * Save creating extra sensor classes
     */
    public void setLocationChangedEvent(DeviceLocationChanged locationChangedEvent) {
        this.locationChangedEvent = locationChangedEvent;
    }

    public int getAnchorRefreshInterval() {
        return anchorRefreshInterval;
    }

    /**
     * Set the interval at which anchors should be automatically re-calculated.
     * locationChangedEvent
     *
     * @param anchorRefreshInterval
     */
    public void setAnchorRefreshInterval(int anchorRefreshInterval) {
        this.anchorRefreshInterval = anchorRefreshInterval;
    }

    public void clearMarkers() {
        for (LocationMarker lm : mLocationMarkers) {
            if (lm.anchorNode != null) {
                if (lm.anchorNode.getAnchor() != null)
                    lm.anchorNode.getAnchor().detach();
                lm.anchorNode.setEnabled(false);
                lm.anchorNode = null;
            }
        }
        mLocationMarkers = new ArrayList<>();
    }

    /**
     * The distance cap for distant markers.
     * ARCore doesn't like markers that are 2000km away :/
     *
     * @return
     */
    public int getDistanceLimit() {
        return distanceLimit;
    }

    /**
     * The distance cap for distant markers.
     * Render distance limit is 30 meters, impossible to change that for now
     * https://github.com/google-ar/sceneform-android-sdk/issues/498
     */
    public void setDistanceLimit(int distanceLimit) {
        this.distanceLimit = distanceLimit;
    }

    public boolean shouldOffsetOverlapping() {
        return offsetOverlapping;
    }

    public boolean shouldRemoveOverlapping() {
        return removeOverlapping;
    }

    /**
     * Attempts to raise markers vertically when they overlap.
     * Needs work!
     *
     * @param offsetOverlapping
     */
    public void setOffsetOverlapping(boolean offsetOverlapping) {
        this.offsetOverlapping = offsetOverlapping;
    }


    /**
     * Remove farthest markers when they overlap
     *
     * @param removeOverlapping
     */
    public void setRemoveOverlapping(boolean removeOverlapping) {
        this.removeOverlapping = removeOverlapping;

//        for (LocationMarker mLocationMarker : mLocationMarkers) {
//            LocationNode anchorNode = mLocationMarker.anchorNode;
//            if (anchorNode != null) {
//                anchorNode.setEnabled(true);
//            }
//        }
    }

    public Location deviceLocation() {
        return sensors != null ? sensors.getDeviceLocation() : null;
    }

    public void processFrame(Frame frame) {
        refreshAnchorsIfRequired(frame);
    }

    /**
     * Force anchors to be re-calculated
     */
    public void refreshAnchors() {
        anchorsNeedRefresh = true;
    }

    private void refreshAnchorsIfRequired(Frame frame) {
        if (sensors == null || !anchorsNeedRefresh || refreshing) {
            return;
        }
        refreshing = true;
        anchorsNeedRefresh = false;
        if (debugEnabled) {
            Log.i(TAG, "Refreshing anchors...");
        }

        Location deviceLocation = deviceLocation();
        float deviceOrientation = sensors.getOrientation();
        if (deviceLocation == null) {
            if (debugEnabled) {
                Log.i(TAG, "Location not yet established.");
            }
            return;
        }

        for (int i = 0; i < mLocationMarkers.size(); i++) {
            try {
                final LocationMarker marker = mLocationMarkers.get(i);

                if (marker.getDistanceGroup() != currentDistanceGroup) {
                    if (marker.anchorNode != null && marker.anchorNode.isEnabled()) {
                        marker.anchorNode.setEnabled(false);
                    }
                    continue;
                } else if (marker.anchorNode != null && !marker.anchorNode.isEnabled()) {
                    marker.anchorNode.setEnabled(true);
                }

                int markerDistance = (int) Math.round(
                        LocationUtils.distance(
                                marker.latitude,
                                deviceLocation.getLatitude(),
                                marker.longitude,
                                deviceLocation.getLongitude(),
                                0,
                                0)
                );
                if (markerDistance > marker.getOnlyRenderWhenWithin()) {
                    // Don't render if this has been set and we are too far away.
                    if (debugEnabled) {
                        Log.i(TAG, "Not rendering. Marker distance: " + markerDistance
                                + " Max render distance: " + marker.getOnlyRenderWhenWithin());
                    }
                    continue;
                }

                float bearing = (float) LocationUtils.bearing(
                        deviceLocation.getLatitude(),
                        deviceLocation.getLongitude(),
                        marker.latitude,
                        marker.longitude);

                float markerBearing = bearing - deviceOrientation;

                // Bearing adjustment can be set if you are trying to
                // correct the heading of north - setBearingAdjustment(10)
                markerBearing = markerBearing + bearingAdjustment + 360;
                markerBearing = markerBearing % 360;

                double rotation = Math.floor(markerBearing);

                if (debugEnabled) {
                    Log.d(TAG, "currentDegree " + deviceOrientation
                            + " bearing " + bearing + " markerBearing " + markerBearing
                            + " rotation " + rotation + " distance " + markerDistance);
                }

                int renderDistance = markerDistance;

                // Limit the distance of the Anchor within the scene.
                // Prevents rendering issues.
                if (renderDistance > distanceLimit)
                    renderDistance = distanceLimit;

                // Adjustment to add markers on horizon, instead of just directly in front of camera
                double heightAdjustment = 0;
                // Math.round(renderDistance * (Math.tan(Math.toRadians(deviceOrientation.pitch)))) - 1.5F;

                // Raise distant markers for better illusion of distance
                // Hacky - but it works as a temporary measure
                int cappedRealDistance = Math.min(markerDistance, (distanceLimit / 2));
                if (renderDistance != markerDistance)
                    heightAdjustment += 0.005F * (cappedRealDistance - renderDistance);

                float z = -Math.min(renderDistance, RENDER_DISTANCE);

                double rotationRadian = Math.toRadians(rotation);

                float zRotated = (float) (z * Math.cos(rotationRadian));
                float xRotated = (float) -(z * Math.sin(rotationRadian));

                float y = frame.getCamera().getDisplayOrientedPose().ty() + (float) heightAdjustment;

                resetLocationMarker(marker);

                // Don't immediately assign newly created anchor in-case of exceptions
                Pose translation = Pose.makeTranslation(xRotated, y, zRotated);
                Anchor newAnchor = mSession.createAnchor(
                        frame.getCamera()
                                .getDisplayOrientedPose()
                                .compose(translation)
                                .extractTranslation()
                );
                if(marker.anchorNode == null) {
                    marker.anchorNode = new LocationNode(newAnchor, marker, this);
                    marker.anchorNode.setParent(mArSceneView.getScene());
                    marker.anchorNode.addChild(marker.node);

                    if(shouldOffsetOverlapping()) {
                        calculateMarkerHeight(marker, markerDistance);
                    }
                } else marker.anchorNode.setAnchor(newAnchor);
                marker.anchorNode.setScalingMode(LocationMarker.ScalingMode.GRADUAL_TO_MAX_RENDER_DISTANCE);

                marker.node.setLocalPosition(Vector3.zero());

                if (marker.getRenderEvent() != null) {
                    marker.anchorNode.setRenderEvent(marker.getRenderEvent());
                }

                marker.anchorNode.setScaleModifier(marker.getScaleModifier());
                marker.anchorNode.setScalingMode(marker.getScalingMode());
                marker.anchorNode.setGradualScalingMaxScale(marker.getGradualScalingMaxScale());
                marker.anchorNode.setGradualScalingMinScale(marker.getGradualScalingMinScale());
                if(!shouldOffsetOverlapping()) {
                    calculateMarkerHeight(marker, markerDistance);
                }

                if (minimalRefreshing)
                    marker.anchorNode.scaleAndRotate();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        refreshing = false;
        //this is bad, you should feel bad
//        System.gc();
    }

    private void calculateMarkerHeight(LocationMarker marker, int markerDistance) {

        // Locations further than RENDER_DISTANCE are remapped to be rendered closer.
        // => height differential also has to ensure the remap is correct
        if (markerDistance > RENDER_DISTANCE) {
            float renderHeight = RENDER_DISTANCE * marker.getHeight() / markerDistance;
            marker.anchorNode.setHeight(renderHeight);
        } else {
            marker.anchorNode.setHeight(marker.anchorNode.getHeight());
        }
    }

    private void resetLocationMarker(LocationMarker marker) {
        if (marker.anchorNode != null && marker.anchorNode.getAnchor() != null) {
            marker.anchorNode.getAnchor().detach();
        }
    }

    /**
     * Adjustment for compass bearing.
     *
     * @return
     */
    public int getBearingAdjustment() {
        return bearingAdjustment;
    }

    /**
     * Adjustment for compass bearing.
     * You may use this for a custom method of improving precision.
     *
     * @param i
     */
    public void setBearingAdjustment(int i) {
        bearingAdjustment = i;
        anchorsNeedRefresh = true;
    }

    public void setCurrentDistanceGroup(int currentDistanceGroup) {
        this.currentDistanceGroup = currentDistanceGroup;
    }

    public int getCurrentDistanceGroup() {
        return currentDistanceGroup;
    }

    public int getDistanceGroupSize() {
        return distanceGroupSize;
    }


    void startCalculationTask() {
        anchorRefreshTask.run();
    }

    public void stopCalculationTask() {
        mHandler.removeCallbacks(anchorRefreshTask);
    }

    public void resetDistanceLimit() {
        this.distanceLimit = DEFAULT_DISTANCE_LIMIT;
    }

    public int getIteration() {
        return iteration;
    }

    public void setIteration(int iteration) {
        this.iteration = iteration;
    }
}
