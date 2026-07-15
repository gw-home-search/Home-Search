package com.home.infrastructure.web.internaladmin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.home.application.coordinate.override.CoordinateOverrideAdminService;
import com.home.application.coordinate.override.CoordinateOverrideApprovalCommand;
import com.home.application.coordinate.override.CoordinateOverrideApprovalResult;
import com.home.application.ingest.metadata.admin.MetadataAdminModels.ActionResult;
import com.home.application.ingest.metadata.admin.MetadataAdminService;
import com.home.global.error.ApiExceptionHandler;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({InternalAdminCoordinateController.class, InternalAdminMetadataController.class})
@TestPropertySource(properties = "home.admin.internal.enabled=true")
@Import(ApiExceptionHandler.class)
class InternalAdminControllersContractTest {
    private static final UUID ACCOUNT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired
    MockMvc mvc;

    @MockitoBean
    CoordinateOverrideAdminService coordinateService;

    @MockitoBean
    MetadataAdminService metadataService;

    @Test
    @DisplayName("좌표 override actor는 principal에서 파생하고 권한 누락을 거부한다")
    void coordinateOverrideDerivesActorFromPrincipalAndRejectsMissingPermission() throws Exception {
        given(coordinateService.approve(eq("1168010300101400001"), any()))
                .willReturn(new CoordinateOverrideApprovalResult(
                        "1168010300101400001", new BigDecimal("37.5"), new BigDecimal("127.0"), true));

        mvc.perform(put("/internal/v1/admin/coordinates/1168010300101400001/override")
                        .requestAttr(InternalAdminPrincipal.REQUEST_ATTRIBUTE, principal("COORDINATE_WRITE"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                    {"latitude":37.5,"longitude":127.0,"reason":"official source",
                     "approvedBy":"forged-browser-actor"}
                    """))
                .andExpect(status().isOk());

        ArgumentCaptor<CoordinateOverrideApprovalCommand> command =
                ArgumentCaptor.forClass(CoordinateOverrideApprovalCommand.class);
        verify(coordinateService).approve(eq("1168010300101400001"), command.capture());
        assertThat(command.getValue().approvedBy()).isEqualTo(ACCOUNT_ID.toString());

        mvc.perform(put("/internal/v1/admin/coordinates/1168010300101400001/override")
                        .requestAttr(InternalAdminPrincipal.REQUEST_ATTRIBUTE, principal("COORDINATE_READ"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"latitude\":37.5,\"longitude\":127.0,\"reason\":\"official source\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("metadata retry actor는 principal에서 파생한다")
    void metadataRetryDerivesActorFromPrincipal() throws Exception {
        given(metadataService.retry(eq(501L), eq(ACCOUNT_ID.toString()), eq("source updated")))
                .willReturn(new ActionResult(true));

        mvc.perform(post("/internal/v1/admin/metadata/501/retry")
                        .requestAttr(InternalAdminPrincipal.REQUEST_ATTRIBUTE, principal("METADATA_RETRY"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"source updated\",\"actor\":\"forged-browser-actor\"}"))
                .andExpect(status().isOk());

        verify(metadataService).retry(501L, ACCOUNT_ID.toString(), "source updated");
    }

    @Test
    @DisplayName("모든 read hold alias operation은 대응 권한으로 노출한다")
    void exposesEveryPermissionBoundReadHoldAndAliasOperation() throws Exception {
        InternalAdminPrincipal principal = new InternalAdminPrincipal(
                ACCOUNT_ID,
                "operator",
                Set.of("OPERATOR"),
                Set.of("COORDINATE_READ", "METADATA_READ", "METADATA_HOLD", "METADATA_ALIAS_MANAGE"),
                "request-all");

        mvc.perform(get("/internal/v1/admin/coordinates/pending")
                        .requestAttr(InternalAdminPrincipal.REQUEST_ATTRIBUTE, principal))
                .andExpect(status().isOk());
        mvc.perform(get("/internal/v1/admin/coordinates/pending/summary")
                        .requestAttr(InternalAdminPrincipal.REQUEST_ATTRIBUTE, principal))
                .andExpect(status().isOk());
        mvc.perform(get("/internal/v1/admin/metadata/pending")
                        .requestAttr(InternalAdminPrincipal.REQUEST_ATTRIBUTE, principal))
                .andExpect(status().isOk());
        mvc.perform(get("/internal/v1/admin/metadata/pending/summary")
                        .requestAttr(InternalAdminPrincipal.REQUEST_ATTRIBUTE, principal))
                .andExpect(status().isOk());
        mvc.perform(get("/internal/v1/admin/metadata/501")
                        .requestAttr(InternalAdminPrincipal.REQUEST_ATTRIBUTE, principal))
                .andExpect(status().isOk());
        mvc.perform(get("/internal/v1/admin/metadata/pnu-aliases")
                        .requestAttr(InternalAdminPrincipal.REQUEST_ATTRIBUTE, principal))
                .andExpect(status().isOk());
        mvc.perform(post("/internal/v1/admin/metadata/501/hold")
                        .requestAttr(InternalAdminPrincipal.REQUEST_ATTRIBUTE, principal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"manual hold\"}"))
                .andExpect(status().isOk());
        mvc.perform(
                        post("/internal/v1/admin/metadata/pnu-aliases")
                                .requestAttr(InternalAdminPrincipal.REQUEST_ATTRIBUTE, principal)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"canonicalPrefix\":\"11110101\",\"sourcePrefix\":\"11110102\",\"reason\":\"boundary change\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/internal/v1/admin/metadata/pnu-aliases/41/approve")
                        .requestAttr(InternalAdminPrincipal.REQUEST_ATTRIBUTE, principal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"verified\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/internal/v1/admin/metadata/pnu-aliases/41/disable")
                        .requestAttr(InternalAdminPrincipal.REQUEST_ATTRIBUTE, principal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"obsolete\"}"))
                .andExpect(status().isOk());
    }

    private InternalAdminPrincipal principal(String permission) {
        return new InternalAdminPrincipal(ACCOUNT_ID, "operator", Set.of("OPERATOR"), Set.of(permission), "request-1");
    }
}
