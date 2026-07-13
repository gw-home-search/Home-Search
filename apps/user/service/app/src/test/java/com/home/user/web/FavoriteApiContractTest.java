package com.home.user.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

import com.home.application.favorite.GetFavoriteComplex;
import com.home.application.favorite.ListFavoriteComplexes;
import com.home.application.favorite.RemoveFavoriteComplex;
import com.home.application.favorite.SaveFavoriteComplex;
import com.home.application.favorite.port.FavoriteComplexRepository.FavoritePage;
import com.home.domain.user.favorite.FavoriteComplex;
import com.home.user.security.AuthenticatedUserPrincipal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class FavoriteApiContractTest {
    private static final Instant SAVED_AT = Instant.parse("2026-07-13T06:00:00Z");

    @Test
    void exposesListAndSingleStatusWithoutAcceptingUserId() throws Exception {
        var save = mock(SaveFavoriteComplex.class);
        var remove = mock(RemoveFavoriteComplex.class);
        var getFavorite = mock(GetFavoriteComplex.class);
        var list = mock(ListFavoriteComplexes.class);
        when(getFavorite.execute(42, 501)).thenReturn(Optional.of(new FavoriteComplex(42, 501, SAVED_AT)));
        when(getFavorite.execute(42, 502)).thenReturn(Optional.empty());
        when(list.execute(42, 0, 20)).thenReturn(new FavoritePage(List.of(new FavoriteComplex(42, 501, SAVED_AT)), 1));
        var controller = new FavoriteController(save, remove, getFavorite, list);
        var principal = new AuthenticatedUserPrincipal(42);

        assertThat(controller.get(principal, 501)).isEqualTo(new FavoriteController.FavoriteStatusResponse(501, true, SAVED_AT));
        assertThat(controller.get(principal, 502)).isEqualTo(new FavoriteController.FavoriteStatusResponse(502, false, null));
        assertThat(controller.list(principal, 0, 20)).isEqualTo(new FavoriteController.FavoriteListResponse(
            List.of(new FavoriteController.FavoriteItemResponse(501, SAVED_AT)), 0, 20, 1, 1));
    }

    @Test
    void savesAndRemovesIdempotentlyWithNoRequestBody() throws Exception {
        var save = mock(SaveFavoriteComplex.class);
        var remove = mock(RemoveFavoriteComplex.class);
        var controller = new FavoriteController(save, remove, mock(GetFavoriteComplex.class), mock(ListFavoriteComplexes.class));
        controller.save(new AuthenticatedUserPrincipal(42), 501);
        controller.remove(new AuthenticatedUserPrincipal(42), 501);
        verify(save).execute(42, 501);
        verify(remove).execute(42, 501);
    }
}
