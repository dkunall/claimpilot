package com.claimpilot.product.domain.rating;

import com.claimpilot.product.domain.LineOfBusiness;
import com.claimpilot.product.domain.ProductVersion;
import com.claimpilot.product.domain.RatingException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Prices an input against one product version. Pure: the same version and input always give the same
 * premium, which is what lets an old policy be re-rated for an endorsement years later.
 */
public final class RatingEngine {

    private final Map<LineOfBusiness, LineRating> lines = new EnumMap<>(LineOfBusiness.class);

    public RatingEngine(List<LineRating> strategies) {
        strategies.forEach(s -> lines.put(s.line(), s));
    }

    public static RatingEngine standard() {
        return new RatingEngine(List.of(new HealthRating(), new MotorRating()));
    }

    public Premium price(ProductVersion product, RatingInput input) {
        LineRating line = lines.get(product.line());
        if (line == null) {
            throw new RatingException("no rating strategy for " + product.line());
        }
        RatingContext ctx = new RatingContext(product, input);
        line.chain().forEach(step -> step.apply(ctx));
        long net = ctx.premiumMinor().setScale(0, RoundingMode.HALF_EVEN).longValueExact();
        long tax = Steps.pct(BigDecimal.valueOf(net), product.rating().taxPct()).setScale(0, RoundingMode.HALF_EVEN)
                .longValueExact();
        var lines = new java.util.ArrayList<>(ctx.lines());
        lines.add(new BreakdownLine("tax", product.rating().taxPct().toPlainString() + "%", net + tax));
        return new Premium(net, tax, net + tax, lines);
    }
}
