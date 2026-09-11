package com.misl.paymentrouter.model;

/**
 * The dummy Digital Financial Service Providers this router knows about.
 *
 * <p><b>Why an enum instead of a plain String?</b>
 * <ul>
 *   <li><b>The compiler enforces the set.</b> A typo like {@code "DFSP_C"} cannot reach the
 *       service layer - it fails while Jackson is still parsing the request body, which
 *       Spring turns into {@code 400 Bad Request} for free. With a String, the typo would
 *       travel all the way into our business logic before anything noticed.</li>
 *   <li><b>Exhaustive switches.</b> In {@code FeeCalculator} and later in the connector
 *       registry, a {@code switch} over an enum is checked by the compiler: add
 *       {@code DFSP_C} here and every switch that does not handle it stops compiling.
 *       That turns "I forgot to update one place" from a runtime bug into a build error.</li>
 *   <li><b>It documents itself.</b> This file is the definitive list of supported providers.</li>
 * </ul>
 *
 * <p><b>The honest trade-off:</b> Step 1 said "adding DFSP-C should be a YAML entry".
 * With an enum it is a YAML entry <i>plus</i> a line here. That is acceptable, because a new
 * DFSP needs a new connector class in Step 6 anyway (each real provider has a different API),
 * so it was never going to be config-only. We buy type safety for one extra line.
 */
public enum Dfsp {

    /** "AlphaPay" - percentage-fee provider, our bKash analogy. */
    DFSP_A,

    /** "BetaCash" - flat-fee provider, our Nagad analogy. */
    DFSP_B
}
