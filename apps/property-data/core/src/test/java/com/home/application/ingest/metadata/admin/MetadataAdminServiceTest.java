package com.home.application.ingest.metadata.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.home.application.ingest.metadata.admin.MetadataAdminModels.ActionResult;
import com.home.application.ingest.metadata.admin.MetadataAdminModels.Alias;
import com.home.application.ingest.metadata.admin.MetadataAdminModels.Detail;
import com.home.application.ingest.metadata.admin.MetadataAdminModels.Pending;
import com.home.application.ingest.metadata.admin.MetadataAdminModels.Summary;
import com.home.domain.complex.metadata.OdcloudPnuAliasStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MetadataAdminServiceTest {

    @Test
    @DisplayName("metadata admin 조회는 page 범위를 검증하고 repository에 위임한다")
    void readsPendingWithValidatedPage() {
        FakeMetadataAdminRepository repository = new FakeMetadataAdminRepository();
        MetadataAdminService service = new MetadataAdminService(repository);

        List<Pending> pending = service.findPending(50, 10);

        assertThat(pending).isSameAs(repository.pending);
        assertThat(repository.limit).isEqualTo(50);
        assertThat(repository.offset).isEqualTo(10);
        assertThat(service.summary()).isSameAs(repository.summary);
        assertThat(service.detail(501L)).isSameAs(repository.detail);
        assertThat(repository.detailComplexId).isEqualTo(501L);
        assertThat(service.aliases()).isSameAs(repository.aliases);
    }

    @Test
    @DisplayName("metadata admin page 범위가 잘못되면 요청 오류로 거절한다")
    void rejectsInvalidPage() {
        MetadataAdminService service = new MetadataAdminService(new FakeMetadataAdminRepository());

        assertThatThrownBy(() -> service.findPending(0, 0))
                .isInstanceOf(InvalidMetadataAdminRequestException.class)
                .hasMessage("invalid page");
        assertThatThrownBy(() -> service.findPending(201, 0))
                .isInstanceOf(InvalidMetadataAdminRequestException.class)
                .hasMessage("invalid page");
        assertThatThrownBy(() -> service.findPending(1, -1))
                .isInstanceOf(InvalidMetadataAdminRequestException.class)
                .hasMessage("invalid page");
    }

    @Test
    @DisplayName("metadata admin 결정 요청은 actor와 reason을 trim한 뒤 repository에 전달한다")
    void trimsDecisionFields() {
        FakeMetadataAdminRepository repository = new FakeMetadataAdminRepository();
        MetadataAdminService service = new MetadataAdminService(repository);

        ActionResult retry = service.retry(501L, " operator ", " updated ");
        ActionResult hold = service.hold(502L, " reviewer ", " wait ");
        ActionResult approve = service.approveAlias(7L, " approver ", " verified ");
        ActionResult disable = service.disableAlias(8L, " disabler ", " duplicate ");

        assertThat(retry).isSameAs(repository.retryResult);
        assertThat(hold).isSameAs(repository.holdResult);
        assertThat(approve).isSameAs(repository.approveResult);
        assertThat(disable).isSameAs(repository.disableResult);
        assertThat(repository.calls)
                .containsExactly(
                        "retry:501:operator:updated",
                        "hold:502:reviewer:wait",
                        "approve:7:approver:verified",
                        "disable:8:disabler:duplicate");
    }

    @Test
    @DisplayName("metadata admin 결정 요청은 actor와 reason이 비어 있으면 거절한다")
    void rejectsInvalidDecisionFields() {
        MetadataAdminService service = new MetadataAdminService(new FakeMetadataAdminRepository());

        assertThatThrownBy(() -> service.retry(501L, " ", "updated"))
                .isInstanceOf(InvalidMetadataAdminRequestException.class)
                .hasMessage("actor and reason are required");
        assertThatThrownBy(() -> service.hold(501L, "operator", " "))
                .isInstanceOf(InvalidMetadataAdminRequestException.class)
                .hasMessage("actor and reason are required");
        assertThatThrownBy(() -> service.approveAlias(7L, null, "verified"))
                .isInstanceOf(InvalidMetadataAdminRequestException.class)
                .hasMessage("actor and reason are required");
        assertThatThrownBy(() -> service.disableAlias(7L, "operator", null))
                .isInstanceOf(InvalidMetadataAdminRequestException.class)
                .hasMessage("actor and reason are required");
    }

    @Test
    @DisplayName("metadata admin alias 제안은 8자리 prefix와 서로 다른 값을 요구한다")
    void validatesAliasPrefixes() {
        FakeMetadataAdminRepository repository = new FakeMetadataAdminRepository();
        MetadataAdminService service = new MetadataAdminService(repository);

        Alias alias = service.proposeAlias("41461262", "41461263", " operator ", " source confirmed ");

        assertThat(alias).isSameAs(repository.proposedAlias);
        assertThat(repository.calls).containsExactly("propose:41461262:41461263:operator:source confirmed");
        assertThatThrownBy(() -> service.proposeAlias("4146126", "41461263", "operator", "reason"))
                .isInstanceOf(InvalidMetadataAdminRequestException.class)
                .hasMessage("prefix must be 8 digits");
        assertThatThrownBy(() -> service.proposeAlias("41461262", "41461262", "operator", "reason"))
                .isInstanceOf(InvalidMetadataAdminRequestException.class)
                .hasMessage("prefixes must differ");
    }

    private static final class FakeMetadataAdminRepository implements MetadataAdminRepository {
        private final List<Pending> pending = List.of();
        private final Summary summary = new Summary(1, Map.of("UNAVAILABLE", 1L));
        private final Detail detail = new Detail(null, List.of(), List.of());
        private final ActionResult retryResult = new ActionResult(true);
        private final ActionResult holdResult = new ActionResult(false);
        private final ActionResult approveResult = new ActionResult(true);
        private final ActionResult disableResult = new ActionResult(true);
        private final Alias proposedAlias = new Alias(
                7L, "41461262", "41461263", OdcloudPnuAliasStatus.PENDING, "source confirmed", null, null, null, null);
        private final List<Alias> aliases = List.of(proposedAlias);
        private final List<String> calls = new ArrayList<>();
        private int limit;
        private int offset;
        private long detailComplexId;

        @Override
        public List<Pending> findPending(int limit, int offset) {
            this.limit = limit;
            this.offset = offset;
            return pending;
        }

        @Override
        public Summary summary() {
            return summary;
        }

        @Override
        public Detail detail(long complexId) {
            this.detailComplexId = complexId;
            return detail;
        }

        @Override
        public ActionResult retry(long complexId, String actor, String reason) {
            calls.add("retry:" + complexId + ":" + actor + ":" + reason);
            return retryResult;
        }

        @Override
        public ActionResult hold(long complexId, String actor, String reason) {
            calls.add("hold:" + complexId + ":" + actor + ":" + reason);
            return holdResult;
        }

        @Override
        public List<Alias> aliases() {
            return aliases;
        }

        @Override
        public Alias proposeAlias(String canonicalPrefix, String sourcePrefix, String actor, String reason) {
            calls.add("propose:" + canonicalPrefix + ":" + sourcePrefix + ":" + actor + ":" + reason);
            return proposedAlias;
        }

        @Override
        public ActionResult approveAlias(long aliasId, String actor, String reason) {
            calls.add("approve:" + aliasId + ":" + actor + ":" + reason);
            return approveResult;
        }

        @Override
        public ActionResult disableAlias(long aliasId, String actor, String reason) {
            calls.add("disable:" + aliasId + ":" + actor + ":" + reason);
            return disableResult;
        }
    }
}
