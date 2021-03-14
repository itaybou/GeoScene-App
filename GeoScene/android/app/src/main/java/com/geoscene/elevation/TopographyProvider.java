package com.geoscene.elevation;

import com.geoscene.utils.mercator.BoundingBoxCenter;

import java.util.concurrent.CountDownLatch;

public interface TopographyProvider {

    static void fetchTopographyData(BoundingBoxCenter bbox, CountDownLatch latch) {};
    Raster getRaster();
}