package com.misl.paymentrouter.model;

/**
 * How a provider charges for sending money.
 *
 * <p>Two models is not decoration - it is the reason a quote API has to exist.
 * If every provider charged the same way, the frontend could compute the fee itself and
 * {@code POST /api/quotes} would be pointless. Because AlphaPay charges a percentage and
 * BetaCash charges a flat amount, <b>the price depends on who is sending</b>, and only the
 * router (and later, only the provider itself) can work it out.
 *
 * <p>Both models are taken from how real mobile-money providers actually price transfers.
 */
public enum FeeType {

    /**
     * Fee is a percentage of the amount, optionally clamped by a minimum and a maximum.
     * Typical of bKash-style "Send Money" pricing.
     */
    PERCENTAGE,

    /**
     * Fee is a fixed amount regardless of how much is sent.
     * Typical of flat-rate providers - cheap for large transfers, expensive for small ones.
     */
    FLAT
}
