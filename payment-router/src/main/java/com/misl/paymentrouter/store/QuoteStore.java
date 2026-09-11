package com.misl.paymentrouter.store;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.misl.paymentrouter.model.Quote;

/**
 * Keeps issued quotes in memory so a later request can look one up by id.
 *
 * <h2>Why a store at all, when Step 3 has no transfer yet?</h2>
 * A {@code quoteId} that is written into a response and then immediately forgotten is a lie -
 * it looks like a handle but refers to nothing. Either the quote is stored or the id should not
 * exist. Since Step 6's transfer is built entirely around "give me back the quoteId you were
 * issued", storing it is part of quoting, not part of transferring.
 *
 * <h2>Why a Map and not a database</h2>
 * The assignment rules this out, and for a simulator it is the right call anyway: this is a
 * {@code Map}, and {@code ConcurrentHashMap} <i>is</i> a map, with no extra container, no
 * driver, no schema and no network hop. Adding Redis here to store a handful of short-lived
 * objects would be textbook fake complexity.
 *
 * <h2>Why ConcurrentHashMap specifically</h2>
 * Tomcat serves requests on a thread pool, so two quotes can be created at the same moment on
 * different threads. A plain {@code HashMap} under concurrent writes can corrupt its internal
 * structure - historically an infinite loop on resize, which pins a CPU core at 100%.
 * {@code ConcurrentHashMap} is safe for concurrent use and locks only the affected bucket.
 *
 * <p>This class is also a Spring singleton - exactly one instance exists - which is what makes
 * the map shared across every request. A new instance per request would store each quote in its
 * own map and every lookup would miss.
 *
 * <h2>Known limitations, stated openly</h2>
 * <ul>
 *   <li><b>Everything is lost on restart.</b> A quote issued before a restart cannot be found
 *       after it. Accepted: quotes live five minutes.</li>
 *   <li><b>Nothing evicts expired quotes</b>, so the map grows for as long as the process runs.
 *       Harmless for a demo; a real system needs eviction or a TTL cache.</li>
 * </ul>
 * Both are consequences of the no-database constraint and belong in the final README.
 */
@Component
public class QuoteStore {

    private final Map<String, Quote> quotes = new ConcurrentHashMap<>();

    /** Stores a quote, keyed by its id. */
    public void save(Quote quote) {
        quotes.put(quote.quoteId(), quote);
    }

    /**
     * Finds a quote by id.
     *
     * <p>Returns {@link Optional} rather than {@code null} so the caller cannot forget that
     * "not found" is a normal outcome - the type itself asks the question. Returning null
     * invites the NullPointerException that Optional exists to prevent.
     */
    public Optional<Quote> findById(String quoteId) {
        return Optional.ofNullable(quotes.get(quoteId));
    }

    /** How many quotes are currently held. Used by tests and useful for diagnostics. */
    public int size() {
        return quotes.size();
    }
}
