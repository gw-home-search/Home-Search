package com.home.infrastructure.web.internaladmin;

import com.home.application.coordinate.override.CoordinateOverrideAdminService;
import com.home.application.coordinate.override.CoordinateOverrideApprovalCommand;
import com.home.application.coordinate.override.CoordinateOverrideApprovalResult;
import com.home.application.coordinate.override.CoordinatePendingComplex;
import com.home.application.coordinate.override.CoordinatePendingSummary;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/admin/coordinates")
@ConditionalOnProperty(name = "home.admin.internal.enabled", havingValue = "true")
@Validated
public class InternalAdminCoordinateController {
    private final CoordinateOverrideAdminService service;

    public InternalAdminCoordinateController(CoordinateOverrideAdminService service) {
        this.service = service;
    }

    @GetMapping("/pending")
    public List<CoordinatePendingComplex> pending(
            @RequestParam(defaultValue = "50") @Positive int limit,
            @RequestParam(defaultValue = "0") @PositiveOrZero int offset,
            HttpServletRequest servletRequest) {
        InternalAdminPrincipal.from(servletRequest).require("COORDINATE_READ");
        return service.findPendingComplexes(limit, offset);
    }

    @GetMapping("/pending/summary")
    public CoordinatePendingSummary summary(HttpServletRequest servletRequest) {
        InternalAdminPrincipal.from(servletRequest).require("COORDINATE_READ");
        return service.findPendingSummary();
    }

    @PutMapping("/{pnu}/override")
    public ResponseEntity<CoordinateOverrideApprovalResult> override(
            @PathVariable @Pattern(regexp = "\\d{19}") String pnu,
            @Valid @RequestBody OverrideRequest request,
            HttpServletRequest servletRequest) {
        InternalAdminPrincipal principal = InternalAdminPrincipal.from(servletRequest);
        principal.require("COORDINATE_WRITE");
        return ResponseEntity.ok(service.approve(
                pnu,
                new CoordinateOverrideApprovalCommand(
                        pnu, request.latitude(), request.longitude(), request.reason(), principal.actor())));
    }

    public record OverrideRequest(
            @NotNull @DecimalMin("33.0") @DecimalMax("39.0") BigDecimal latitude,

            @NotNull @DecimalMin("124.0") @DecimalMax("132.0")
            BigDecimal longitude,

            @NotBlank @Size(max = 1000) String reason) {}
}
