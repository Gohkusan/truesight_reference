package com.truesight.backend.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesight.backend.domain.Company;
import com.truesight.backend.domain.Criticality;
import com.truesight.backend.domain.Relationship;
import com.truesight.backend.domain.RelationshipType;
import org.junit.jupiter.api.Test;

/** The propagation factors are documented model assumptions; pin them so a change is deliberate. */
class SimulationServiceTest {

    private static Relationship rel(Criticality c, Integer dependencyPercent) {
        Relationship r = new Relationship(new Company("A", "A"), new Company("B", "B"), RelationshipType.SUPPLIER);
        r.setCriticality(c);
        r.setDependencyPercent(dependencyPercent);
        return r;
    }

    @Test
    void singleSourceTransmitsFully() {
        assertThat(SimulationService.transmission(rel(Criticality.SINGLE_SOURCE, null))).isEqualTo(1.0);
    }

    @Test
    void dualAndDiversifiedTransmitPartially() {
        assertThat(SimulationService.transmission(rel(Criticality.DUAL_SOURCE, null))).isEqualTo(0.5);
        assertThat(SimulationService.transmission(rel(Criticality.DIVERSIFIED, null))).isEqualTo(0.25);
    }

    @Test
    void disclosedDependencyCanRaiseButNotLowerTheCriticalityPrior() {
        // Diversified but 80% from one supplier: 0.80 * 0.5 = 0.40 > 0.25 prior.
        assertThat(SimulationService.transmission(rel(Criticality.DIVERSIFIED, 80))).isEqualTo(0.40);
        // Single-source with a stated 30% share still transmits fully: no alternative for that 30%.
        assertThat(SimulationService.transmission(rel(Criticality.SINGLE_SOURCE, 30))).isEqualTo(1.0);
    }
}
