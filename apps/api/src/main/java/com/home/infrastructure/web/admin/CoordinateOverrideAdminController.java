package com.home.infrastructure.web.admin;

import java.math.BigDecimal;
import java.util.List;

import com.home.application.coordinate.CoordinateOverrideAdminService;
import com.home.application.coordinate.CoordinateOverrideApprovalCommand;
import com.home.application.coordinate.CoordinateOverrideApprovalResult;
import com.home.application.coordinate.CoordinatePendingComplex;
import com.home.application.coordinate.CoordinatePendingSummary;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/coordinates")
@ConditionalOnProperty(name = "home.admin.coordinate-override.enabled", havingValue = "true")
public class CoordinateOverrideAdminController {

	private final CoordinateOverrideAdminService service;

	public CoordinateOverrideAdminController(CoordinateOverrideAdminService service) {
		this.service = service;
	}

	@GetMapping("/pending")
	public ResponseEntity<List<CoordinatePendingComplex>> getPendingCoordinates(
		@RequestParam(value = "limit", defaultValue = "50") Integer limit,
		@RequestParam(value = "offset", defaultValue = "0") Integer offset
	) {
		return ResponseEntity.ok(service.findPendingComplexes(limit, offset));
	}

	@GetMapping("/pending/summary")
	public ResponseEntity<CoordinatePendingSummary> getPendingCoordinateSummary() {
		return ResponseEntity.ok(service.findPendingSummary());
	}

	@PutMapping("/{pnu}/override")
	public ResponseEntity<CoordinateOverrideApprovalResult> approveOverride(
		@PathVariable("pnu") @Pattern(regexp = "\\d{19}") String pnu,
		@Valid @RequestBody CoordinateOverrideApprovalRequest request
	) {
		return ResponseEntity.ok(service.approve(pnu, request.toCommand(pnu)));
	}

	public record CoordinateOverrideApprovalRequest(
		@NotNull
		@DecimalMin("33.0")
		@DecimalMax("39.0")
		BigDecimal latitude,
		@NotNull
		@DecimalMin("124.0")
		@DecimalMax("132.0")
		BigDecimal longitude,
		@Size(max = 1000)
		String reason,
		@NotBlank
		@Size(max = 128)
		String approvedBy
	) {

		CoordinateOverrideApprovalCommand toCommand(String pnu) {
			return new CoordinateOverrideApprovalCommand(pnu, latitude, longitude, reason, approvedBy);
		}
	}
}
