package com.fraudengine.config;

import java.sql.SQLException;
import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * Turns a database startup failure into a readable message rather than eighty stack frames.
 *
 * <p>Running the image without a database is the most likely way a reviewer meets this service for
 * the first time, and a wall of driver frames tells them nothing they can act on.
 *
 * <p>Matched on {@link SQLException} and its SQLSTATE rather than on a socket exception, because a
 * refused connection arrives as {@code ConnectException} while an unroutable host arrives as
 * {@code SocketTimeoutException} — targeting either alone leaves the other unhandled. SQLSTATE is
 * also standard, so this carries no dependency on the driver being PostgreSQL.
 */
public class DatabaseUnreachableFailureAnalyzer extends AbstractFailureAnalyzer<SQLException> {

    /** SQLSTATE class 08 is "connection exception" — the driver never reached a working server. */
    private static final String CONNECTION_EXCEPTION_CLASS = "08";

    private static final String INVALID_AUTHORIZATION = "28";

    @Override
    protected FailureAnalysis analyze(Throwable rootFailure, SQLException cause) {
        String sqlState = cause.getSQLState() == null ? "" : cause.getSQLState();

        if (sqlState.startsWith(INVALID_AUTHORIZATION)) {
            return new FailureAnalysis(
                    "The database rejected the supplied credentials, so the application stopped.",
                    "Check DATABASE_USERNAME and DATABASE_PASSWORD against the database you are "
                            + "pointing at. Under Compose these default to fraud/fraud.",
                    cause);
        }

        if (!sqlState.startsWith(CONNECTION_EXCEPTION_CLASS)) {
            return null;
        }

        return new FailureAnalysis(
                """
                The application could not reach its database and has stopped.

                This service needs a running PostgreSQL. It does not start without one, because \
                starting without a database would mean accepting transactions it cannot decide.""",
                """
                Start the database and the service together:

                    docker compose up

                Or point the image at an existing PostgreSQL:

                    docker run -p 8080:8080 \\
                      -e DATABASE_URL=jdbc:postgresql://<host>:5432/fraud \\
                      -e DATABASE_USERNAME=fraud \\
                      -e DATABASE_PASSWORD=fraud \\
                      fraud-rule-engine

                DATABASE_URL defaults to jdbc:postgresql://localhost:5432/fraud.""",
                cause);
    }
}
