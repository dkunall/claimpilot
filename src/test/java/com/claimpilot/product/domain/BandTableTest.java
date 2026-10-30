package com.claimpilot.product.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BandTableTest {

    private static Band band(Integer upTo, String v) {
        return new Band(upTo == null ? null : BigDecimal.valueOf(upTo), new BigDecimal(v));
    }

    private final BandTable age = new BandTable(List.of(band(25, "0.8"), band(35, "1.0"), band(60, "1.9")));

    @Test
    void upperBoundsAreInclusive() {
        assertThat(age.lookup(BigDecimal.valueOf(25))).isEqualByComparingTo("0.8");
        assertThat(age.lookup(new BigDecimal("25.01"))).isEqualByComparingTo("1.0");
        assertThat(age.lookup(BigDecimal.ZERO)).isEqualByComparingTo("0.8");
        assertThat(age.lookup(BigDecimal.valueOf(60))).isEqualByComparingTo("1.9");
    }

    @Test
    void aboveTheLastClosedBandIsAnErrorNotTheLastValue() {
        assertThatThrownBy(() -> age.lookup(BigDecimal.valueOf(61))).isInstanceOf(RatingException.class)
                .hasMessageContaining("above the highest band");
    }

    @Test
    void anOpenLastBandCatchesEverythingAbove() {
        BandTable t = new BandTable(List.of(band(10, "1"), band(null, "2")));
        assertThat(t.lookup(BigDecimal.valueOf(1_000_000))).isEqualByComparingTo("2");
    }

    @Test
    void binarySearchAgreesWithALinearScan() {
        List<Band> bands = new java.util.ArrayList<>();
        for (int i = 1; i <= 97; i++) {
            bands.add(band(i * 3, Integer.toString(i)));
        }
        BandTable t = new BandTable(bands);
        for (int x = 0; x <= 291; x++) {
            int expected = (x + 2) / 3 == 0 ? 1 : (x + 2) / 3;
            assertThat(t.lookup(BigDecimal.valueOf(x)).intValue()).as("x=%d", x).isEqualTo(expected);
        }
    }

    @Test
    void rejectsBadShapes() {
        assertThatThrownBy(() -> new BandTable(List.of(band(10, "1"), band(10, "2"))))
                .isInstanceOf(InvalidProductException.class);
        assertThatThrownBy(() -> new BandTable(List.of(band(null, "1"), band(10, "2"))))
                .isInstanceOf(InvalidProductException.class);
        assertThatThrownBy(() -> new BandTable(List.of())).isInstanceOf(InvalidProductException.class);
    }
}
