package com.bizlama.api.quantity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bizlama.api.domain.Ingredient;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class UnitConversionServiceTest {

    private final UnitConversionService units = new UnitConversionService();

    @Test
    void canonicalizesCompatibleMassVolumeAndCountAliasesExactly() {
        assertThat(units.canonicalize(new BigDecimal("1.250"), "kg"))
                .satisfies(value -> {
                    assertThat(value.quantity()).isEqualByComparingTo("1250");
                    assertThat(value.unit()).isEqualTo("g");
                    assertThat(value.dimension()).isEqualTo(UnitDimension.MASS);
                });
        assertThat(units.canonicalize(new BigDecimal("2.5"), "litres"))
                .satisfies(value -> {
                    assertThat(value.quantity()).isEqualByComparingTo("2500");
                    assertThat(value.unit()).isEqualTo("ml");
                    assertThat(value.dimension()).isEqualTo(UnitDimension.VOLUME);
                });
        assertThat(units.toIngredientBase(
                new Ingredient("eggs", "Eggs", "pieces", true),
                new BigDecimal("12"),
                "pcs"))
                .satisfies(value -> {
                    assertThat(value.quantity()).isEqualByComparingTo("12");
                    assertThat(value.unit()).isEqualTo("each");
                    assertThat(value.dimension()).isEqualTo(UnitDimension.COUNT);
                });
        assertThat(units.convert(new BigDecimal("1500"), "g", "kg").quantity())
                .isEqualByComparingTo("1.5");
    }

    @Test
    void rejectsCrossDimensionAndUnknownConversionsInsteadOfGuessing() {
        assertThat(units.compatible("kg", "grams")).isTrue();
        assertThat(units.compatible("l", "ml")).isTrue();
        assertThat(units.compatible("pieces", "each")).isTrue();
        assertThatThrownBy(() -> units.convert(BigDecimal.ONE, "kg", "ml"))
                .isInstanceOf(IncompatibleUnitException.class)
                .hasMessageContaining("different dimensions");
        assertThatThrownBy(() -> units.toIngredientBase(
                new Ingredient("milk", "Milk", "ml", true),
                BigDecimal.ONE,
                "g"))
                .isInstanceOf(IncompatibleUnitException.class)
                .hasMessageContaining("not compatible");
        assertThatThrownBy(() -> units.canonicalize(BigDecimal.ONE, "cup"))
                .isInstanceOf(IncompatibleUnitException.class)
                .hasMessageContaining("instead of guessing");
    }
}
