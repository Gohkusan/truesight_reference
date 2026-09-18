package com.truesight.backend.domain;

/** Direction of dependency, from the perspective of the FROM company on the edge. */
public enum RelationshipType {
    /** FROM supplies TO (FROM is upstream of TO). */
    SUPPLIER,
    /** FROM is a customer of TO (FROM is downstream of TO). */
    CUSTOMER
}
