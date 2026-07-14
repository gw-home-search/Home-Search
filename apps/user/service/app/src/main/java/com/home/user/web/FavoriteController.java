package com.home.user.web;

import com.home.application.favorite.GetFavoriteComplex;
import com.home.application.favorite.ListFavoriteComplexes;
import com.home.application.favorite.RemoveFavoriteComplex;
import com.home.application.favorite.SaveFavoriteComplex;
import com.home.user.security.AuthenticatedUserPrincipal;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FavoriteController {
    private final SaveFavoriteComplex save;
    private final RemoveFavoriteComplex remove;
    private final GetFavoriteComplex get;
    private final ListFavoriteComplexes list;

    public FavoriteController(
            SaveFavoriteComplex save,
            RemoveFavoriteComplex remove,
            GetFavoriteComplex get,
            ListFavoriteComplexes list) {
        this.save = save;
        this.remove = remove;
        this.get = get;
        this.list = list;
    }

    @GetMapping("/api/v1/favorites")
    FavoriteListResponse list(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var result = list.execute(principal.userId(), page, size);
        List<FavoriteItemResponse> content = result.content().stream()
                .map(value -> new FavoriteItemResponse(value.complexId(), value.savedAt()))
                .toList();
        int totalPages = result.totalElements() == 0 ? 0 : (int) ((result.totalElements() + size - 1) / size);
        return new FavoriteListResponse(content, page, size, result.totalElements(), totalPages);
    }

    @GetMapping("/api/v1/favorites/{complexId}")
    FavoriteStatusResponse get(
            @AuthenticationPrincipal AuthenticatedUserPrincipal principal, @PathVariable long complexId) {
        return get.execute(principal.userId(), complexId)
                .map(value -> new FavoriteStatusResponse(complexId, true, value.savedAt()))
                .orElseGet(() -> new FavoriteStatusResponse(complexId, false, null));
    }

    @PutMapping("/api/v1/favorites/{complexId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void save(@AuthenticationPrincipal AuthenticatedUserPrincipal principal, @PathVariable long complexId) {
        save.execute(principal.userId(), complexId);
    }

    @DeleteMapping("/api/v1/favorites/{complexId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void remove(@AuthenticationPrincipal AuthenticatedUserPrincipal principal, @PathVariable long complexId) {
        remove.execute(principal.userId(), complexId);
    }

    record FavoriteItemResponse(long complexId, Instant savedAt) {}

    record FavoriteStatusResponse(long complexId, boolean favorite, Instant savedAt) {}

    record FavoriteListResponse(
            List<FavoriteItemResponse> content, int page, int size, long totalElements, int totalPages) {}
}
