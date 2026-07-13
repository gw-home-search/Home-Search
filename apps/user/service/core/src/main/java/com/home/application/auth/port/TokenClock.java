package com.home.application.auth.port;
import java.time.Instant;
@FunctionalInterface public interface TokenClock { Instant now(); }
