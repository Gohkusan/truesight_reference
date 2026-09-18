package com.truesight.backend.domain;

/**
 * Every persisted Relationship row is SUPPLIER: the edge points in the direction goods
 * flow, FROM the supplier TO the buyer. A "customer" relationship extracted from a
 * filing ("X is a significant customer of ours") is stored as a SUPPLIER edge
 * filer -> X, because that is the same fact seen from the other side.
 *
 * <p>CUSTOMER is therefore never stored. It exists so API responses can describe an
 * edge RELATIVE TO A SELECTED NODE (AC 4.3: "type (supplier or customer)") — for the
 * node on the FROM side of an edge, the other end is its customer; for the node on the
 * TO side, the other end is its supplier. Keeping a single storage direction makes
 * "what is upstream of X" one query in one direction; see
 * GeminiExtractionService#persistRelationship for the history of why.
 */
public enum RelationshipType {
    SUPPLIER,
    CUSTOMER
}
