package com.geoscene.maps;

import android.graphics.Color;
import android.location.Location;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.MotionEvent;
import android.widget.LinearLayout;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.events.RCTEventEmitter;
import com.geoscene.R;
import com.geoscene.constants.LocationConstants;
import com.geoscene.sensors.DeviceSensors;
import com.geoscene.sensors.DeviceSensorsManager;
import com.geoscene.triangulation.Triangulation;
import com.geoscene.triangulation.TriangulationData;
import com.geoscene.utils.Coordinate;
import com.geoscene.utils.LocationUtils;
import com.geoscene.utils.mercator.BoundingBoxCenter;

import org.osmdroid.api.IGeoPoint;
import org.osmdroid.api.IMapController;
import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.CustomZoomButtonsController;
import org.osmdroid.views.MapView;
import org.osmdroid.views.Projection;
import org.osmdroid.views.overlay.FolderOverlay;
import org.osmdroid.views.overlay.ItemizedIconOverlay;
import org.osmdroid.views.overlay.Overlay;
import org.osmdroid.views.overlay.OverlayItem;
import org.osmdroid.views.overlay.Polygon;
import org.osmdroid.views.overlay.Polyline;
import org.osmdroid.views.overlay.compass.CompassOverlay;
import org.osmdroid.views.overlay.compass.IOrientationConsumer;
import org.osmdroid.views.overlay.compass.IOrientationProvider;
import org.osmdroid.views.overlay.compass.InternalCompassOrientationProvider;
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider;
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class OSMMapView extends LinearLayout implements IOrientationConsumer {

    private ReactContext reactContext;
    private final int REQUEST_PERMISSIONS_REQUEST_CODE = 1;

    public MapView map = null;
    private GeoPoint observer;
    private IMapController mapController;
    private DeviceSensors sensors;
    private boolean useCompassOrientation;
    private boolean useTriangulation;
    private boolean useObserverLocation;
    private boolean enableZoom;
    private boolean enableLocationMarkerTap;
    private boolean showBoundingCircle;

    private List<TriangulationData> triangulationData;

    private InternalCompassOrientationProvider compass;
    private Polygon bboxCircle;
    private MyLocationNewOverlay mLocationOverlay;

    private Polyline lineOfSight;
    private FolderOverlay triangulationLines;
    private ItemizedIconOverlay<OverlayItem> triangulationPoints;

    private ItemizedIconOverlay<OverlayItem> locationMarkers = null;
    private double previousAzimuth;
//    private OrientationProvider orientationProvider;

    private static final long ORIENTATION_CHANGE_ANIMATION_SPEED = 200L;
    private boolean animateToIncludeTriangulationPoints;


    public OSMMapView(ThemedReactContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;

        Configuration.getInstance().load(reactContext, PreferenceManager.getDefaultSharedPreferences(reactContext));
        inflate(reactContext, R.layout.map_layout, this);
        map = (MapView) findViewById(R.id.map);
        map.setTileSource(TileSourceFactory.MAPNIK);
        map.getOverlays().clear();

        sensors = DeviceSensorsManager.getSensors(reactContext);
        mapController = map.getController();

        map.invalidate();
    }

    public void setUseCompassOrientation(boolean useCompassOrientation) {
        this.useCompassOrientation = useCompassOrientation;
        if (useCompassOrientation) {
            compass = new InternalCompassOrientationProvider(reactContext);
            CompassOverlay compassOverlay = new CompassOverlay(reactContext, compass, map);
            compassOverlay.enableCompass();
            map.getOverlays().add(compassOverlay);
            compass.startOrientationProvider(this);
        }
    }

    public void setUseObserverLocation(boolean useObserverLocation) {
        this.useObserverLocation = useObserverLocation;
        if (useObserverLocation) {
            mLocationOverlay = new MyLocationNewOverlay(new GpsMyLocationProvider(reactContext), map);
            mLocationOverlay.setEnableAutoStop(false);
            mLocationOverlay.enableMyLocation();
            if(!useTriangulation) {
                mLocationOverlay.enableFollowLocation();
            }
            map.getOverlays().add(mLocationOverlay);
        }
    }

    public void setEnableZoom(boolean enableZoom) {
        this.enableZoom = enableZoom;
        map.getZoomController().setVisibility(enableZoom ? CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT : CustomZoomButtonsController.Visibility.NEVER);
        map.setMultiTouchControls(enableZoom);
        map.setEnabled(enableZoom);
    }

    public void setShowBoundingCircle(boolean showBoundingCircle) {
        this.showBoundingCircle = showBoundingCircle;
    }

    public void setUseTriangulation(boolean useTriangulation) {
        this.useTriangulation = useTriangulation;
    }

    public void setAnimateToIncludeTriangulationPoints(boolean animateToIncludeTriangulationPoints) {
        this.animateToIncludeTriangulationPoints = animateToIncludeTriangulationPoints;
        if(!animateToIncludeTriangulationPoints) {
            zoomToBoundingBox();
            //mapController.animateTo(observer, map.getZoomLevelDouble(), ORIENTATION_CHANGE_ANIMATION_SPEED, 0f);
        }
    }

    public void setEnableLocationMarkerTap(boolean enableLocationMarkerTap) {
        this.enableLocationMarkerTap = enableLocationMarkerTap;
        Log.d("LOCATION_TAP", String.valueOf(enableLocationMarkerTap));
        if (this.enableLocationMarkerTap) {
            Overlay overlay = new Overlay() {
                @Override
                public boolean onSingleTapConfirmed(MotionEvent e, MapView mapView) {
                    Projection proj = mapView.getProjection();
                    GeoPoint loc = (GeoPoint) proj.fromPixels((int) e.getX(), (int) e.getY());
                    double longitude = loc.getLongitude();
                    double latitude = loc.getLatitude();
                    dispatchSingleTapLocation(latitude, longitude);

                    ArrayList<OverlayItem> markers = new ArrayList<>();
                    OverlayItem item = new OverlayItem("", "", new GeoPoint(latitude, longitude));
                    //                item.setMarker(ContextCompat.getDrawable(context, R.drawable.ic_maps_marker_large));
                    markers.add(item);

                    mapView.getOverlays().remove(locationMarkers);
                    locationMarkers = new ItemizedIconOverlay<>(reactContext, markers, null);
                    mapView.getOverlays().add(locationMarkers);
                    map.invalidate();
                    return true;
                }

                @Override
                public boolean onDoubleTapEvent(MotionEvent e, MapView mapView) {
                    return true;
                }

                @Override
                public boolean onDoubleTap(MotionEvent e, MapView pMapView) {
                    return true;
                }
            };
            map.getOverlays().add(overlay);
        }
    }


    @Override
    public void onOrientationChanged(final float orientationToMagneticNorth, IOrientationProvider source) {
        // this part adjusts the desired map rotation based on device orientation and compass heading
        float azimuth = sensors.getOrientation();
        float t = (360 - azimuth);
        t += t < 0 ? 360 : t > 360 ? -360 : 0;
        t = (int) t;
        t = t / 2;
        t = (int) t;
        t = t * 2;

        if(useTriangulation && Math.abs(previousAzimuth - azimuth) >= 1e-2) { // Reduce azimuth to include only 2 digits (EPSILON DIFF)
            dispatchAzimuth(azimuth);
            Location location = sensors.getDeviceLocation();
            double lat = location.getLatitude(), lon = location.getLongitude();
            List<Coordinate> myArc = Triangulation.getGeodesicArc(200, 1e5, lat, lon, azimuth);

            if(lineOfSight != null)
                map.getOverlays().remove(lineOfSight);
            lineOfSight = new Polyline(map);
            lineOfSight.setPoints(myArc.stream().map(c -> new GeoPoint(c.getLat(), c.getLon())).collect(Collectors.toList()));
            lineOfSight.getOutlinePaint().setColor(Color.RED);
            lineOfSight.getOutlinePaint().setStrokeWidth(4f);
            map.getOverlays().add(lineOfSight);

            if(triangulationLines != null) {
                map.getOverlays().remove(triangulationLines);
            }

            if(triangulationPoints != null) {
                map.getOverlays().remove(triangulationPoints);
            }

            triangulationLines = new FolderOverlay();
            ArrayList<OverlayItem> markers = new ArrayList<>();
            for(TriangulationData triangulation : triangulationData) {
                Coordinate intersection = Triangulation.triangulate(lat, lon, azimuth, triangulation.getLat(), triangulation.getLon(), triangulation.getAzimuth());
                if(intersection != null && LocationUtils.aerialDistance(lat, intersection.getLat(), lon, intersection.getLon()) <= (1e5 - 5e4)) {
                    markers.add(new OverlayItem("", "", new GeoPoint(intersection.getLat(), intersection.getLon())));
                    List<Coordinate> arc = triangulation.getTriangulationArc();
                    Log.d("MapView", String.valueOf(arc.size()));

                    Polyline polyline = new Polyline(map);
                    polyline.setPoints(arc.stream().map(c -> new GeoPoint(c.getLat(), c.getLon())).collect(Collectors.toList()));
                    polyline.getOutlinePaint().setColor(Color.BLACK);
                    polyline.getOutlinePaint().setStrokeWidth(4f);
                    triangulationLines.add(polyline);
                }
            }

                if (!markers.isEmpty()) {
                    triangulationPoints = new ItemizedIconOverlay<>(reactContext, markers, null);
                    map.getOverlays().add(triangulationPoints);
                }
                List<IGeoPoint> bboxPoints = markers.stream().map(OverlayItem::getPoint).collect(Collectors.toList());
                if (bboxPoints.isEmpty() && animateToIncludeTriangulationPoints) {
                    BoundingBoxCenter bbox = new BoundingBoxCenter(new Coordinate(location.getLatitude(), location.getLongitude()), LocationConstants.OBSERVER_BBOX * 2);
                    map.zoomToBoundingBox(new BoundingBox(bbox.getNorth(), bbox.getEast(), bbox.getSouth(), bbox.getWest()), true, 5, map.getZoomLevelDouble() * 100, ORIENTATION_CHANGE_ANIMATION_SPEED * 5);
                    map.zoomToBoundingBox(getPointsBbox(bboxPoints), true, 100, map.getZoomLevelDouble() * 100, ORIENTATION_CHANGE_ANIMATION_SPEED * 5);
                } else if(animateToIncludeTriangulationPoints) {
                    bboxPoints.add(observer);
                    mapController.animateTo(observer, map.getZoomLevelDouble(), ORIENTATION_CHANGE_ANIMATION_SPEED, t);
                    map.zoomToBoundingBox(getPointsBbox(bboxPoints), true, 100, map.getZoomLevelDouble() * 100, ORIENTATION_CHANGE_ANIMATION_SPEED * 5);
                }
                map.getOverlays().add(triangulationLines);
            map.invalidate();
        }
        if(!animateToIncludeTriangulationPoints) {
            mapController.animateTo(observer, map.getZoomLevelDouble(), ORIENTATION_CHANGE_ANIMATION_SPEED, t);
        }
        previousAzimuth = azimuth;
    }


    public void zoomToBoundingBox() {
        map.post(() -> {
            if (map.getMeasuredHeight() > 0 && map.getMeasuredWidth() > 0) {
                observer = new GeoPoint(sensors.getDeviceLocation().getLatitude(), sensors.getDeviceLocation().getLongitude());
                mapController.setCenter(observer);
                Log.d("MEASURE1111", map.getMeasuredHeight() + "," + map.getMeasuredWidth());
                Coordinate observer = new Coordinate(sensors.getDeviceLocation().getLatitude(), sensors.getDeviceLocation().getLongitude());
                BoundingBoxCenter bbox = new BoundingBoxCenter(observer, LocationConstants.OBSERVER_BBOX); // CHANGE TP GLOBAL SETTINGS
                Log.d("BBOX1111", bbox.toString());
                map.zoomToBoundingBox(new BoundingBox(bbox.getNorth(), bbox.getEast(), bbox.getSouth(), bbox.getWest()), false, 5);
                map.zoomToBoundingBox(new BoundingBox(bbox.getNorth(), bbox.getEast(), bbox.getSouth(), bbox.getWest()), true, 5);
                map.invalidate();
            }
        });
    }

    public void zoomToBoundingBox(double latitude, double longitude, int radius, boolean placeMarker) {
        map.post(() -> {
            if (map.getMeasuredHeight() > 0 && map.getMeasuredWidth() > 0) {
                observer = new GeoPoint(latitude, longitude);
                mapController.setCenter(observer);
                List<GeoPoint> points = Polygon.pointsAsCircle(observer, radius * 1000);

                if (!points.isEmpty()) {
                    BoundingBox bbox = getPointsBbox(points);
                    map.zoomToBoundingBox(bbox, true, 5);
                    if (showBoundingCircle) {
                        if (bboxCircle != null)
                            map.getOverlays().remove(bboxCircle);
                        bboxCircle = new Polygon() {
                            @Override
                            public boolean onSingleTapConfirmed(MotionEvent e, MapView mapView) {
                                if (enableLocationMarkerTap) {
                                    Projection proj = mapView.getProjection();
                                    GeoPoint loc = (GeoPoint) proj.fromPixels((int) e.getX(), (int) e.getY());
                                    double longitude = loc.getLongitude();
                                    double latitude = loc.getLatitude();
                                    dispatchSingleTapLocation(latitude, longitude);

                                    ArrayList<OverlayItem> markers = new ArrayList<>();
                                    OverlayItem item = new OverlayItem("", "", new GeoPoint(latitude, longitude));
//                item.setMarker(ContextCompat.getDrawable(context, R.drawable.ic_maps_marker_large));
                                    markers.add(item);

                                    mapView.getOverlays().remove(locationMarkers);
                                    locationMarkers = new ItemizedIconOverlay<>(getContext(), markers, null);
                                    mapView.getOverlays().add(locationMarkers);
                                    mapView.invalidate();
                                }
                                return true;
                            }
                            @Override
                            public boolean onDoubleTapEvent(MotionEvent e, MapView mapView) {
                                return true;
                            }

                            @Override
                            public boolean onDoubleTap(MotionEvent e, MapView pMapView) {
                                return true;
                            }
                        };
                        bboxCircle.setPoints(points);

                        bboxCircle.getFillPaint().setColor(Color.parseColor("#662CA59C"));
                        bboxCircle.getOutlinePaint().setAlpha(0);
                        map.getOverlays().add(bboxCircle);
                    }
                }

                if (placeMarker) {
                    ArrayList<OverlayItem> markers = new ArrayList<>();
                    OverlayItem item = new OverlayItem("", "", new GeoPoint(latitude, longitude));
//                item.setMarker(ContextCompat.getDrawable(context, R.drawable.ic_maps_marker_large));
                    markers.add(item);
                    map.getOverlays().remove(locationMarkers);
                    locationMarkers = new ItemizedIconOverlay<>(getContext(), markers, null);
                    map.getOverlays().add(locationMarkers);
                }
                map.invalidate();
            }
        });
    }


    private <T extends IGeoPoint> BoundingBox getPointsBbox(List<T> points) {
        double north = -90;
        double south = 90;
        double west = 180;
        double east = -180;
        for (T position : points) {
            north = Math.max(position.getLatitude(), north);
            south = Math.min(position.getLatitude(), south);

            west = Math.min(position.getLongitude(), west);
            east = Math.max(position.getLongitude(), east);
        }
        return new BoundingBox(north, east, south, west);
    }


    public void dispatchSingleTapLocation(double latitude, double longitude) {
        WritableMap event = Arguments.createMap();
        event.putDouble("latitude", latitude);
        event.putDouble("longitude", longitude);
        reactContext.getJSModule(RCTEventEmitter.class).receiveEvent(
                getId(),
                "mapSingleTap",
                event);
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        Log.d("VISIBLE", String.valueOf(isVisible));
        if (isVisible) resume();
        else pause();
    }

    private void resume() {
        if (useCompassOrientation) {
            compass.startOrientationProvider(this);
        }
        sensors.resume();
        map.onResume();
    }

    private void pause() {
        if (useCompassOrientation) {
            compass.stopOrientationProvider();
        }
        map.onPause();
        sensors.pause();
    }

    public void setTriangulationData(List<TriangulationData> data) {
        triangulationData = data;
        triangulationData.forEach(t -> t.setTriangulationArc(Triangulation.getGeodesicArc(1000, 1e5, t.getLat(), t.getLon(), t.getAzimuth())));
        if(mLocationOverlay != null) {
            mLocationOverlay.disableFollowLocation();
        }
    }

    public void dispatchAzimuth(float azimuth) {
        WritableMap event = Arguments.createMap();
        event.putDouble("azimuth", azimuth);
        reactContext.getJSModule(RCTEventEmitter.class).receiveEvent(
                getId(),
                "azimuth",
                event);
    }
}