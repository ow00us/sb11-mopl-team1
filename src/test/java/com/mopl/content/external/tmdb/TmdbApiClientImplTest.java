package com.mopl.content.external.tmdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.mopl.content.external.tmdb.dto.TmdbPopularMoviesResponse;
import com.mopl.content.external.tmdb.dto.TmdbPopularTvResponse;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class TmdbApiClientImplTest {

    private static final String ACCESS_TOKEN = "test-token";

    private RestClient.Builder newBuilder() {
        return RestClient.builder()
                .baseUrl("https://api.themoviedb.org/3")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
    }

    @Test
    @DisplayName("getPopularMovies는 인증 헤더를 포함해 요청하고 정상 응답을 파싱한다")
    void getPopularMovies_success_parsesResponse() {
        RestClient.Builder builder = newBuilder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TmdbApiClientImpl client = new TmdbApiClientImpl(builder.build());

        String body = """
                {
                  "page": 1,
                  "total_pages": 500,
                  "results": [
                    {"id": 603, "title": "매트릭스", "overview": "설명",
                     "poster_path": "/path.jpg", "genre_ids": [28, 878]}
                  ]
                }
                """;

        server.expect(requestTo("https://api.themoviedb.org/3/movie/popular?page=1"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + ACCESS_TOKEN))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        TmdbPopularMoviesResponse response = client.getPopularMovies(1);

        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).title()).isEqualTo("매트릭스");
        assertThat(response.results().get(0).genreIds()).containsExactly(28, 878);
        server.verify();
    }

    @Test
    @DisplayName("getPopularMovies는 5xx 응답을 TmdbApiException으로 변환한다")
    void getPopularMovies_serverError_throwsTmdbApiException() {
        RestClient.Builder builder = newBuilder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TmdbApiClientImpl client = new TmdbApiClientImpl(builder.build());

        server.expect(requestTo("https://api.themoviedb.org/3/movie/popular?page=1"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.getPopularMovies(1))
                .isInstanceOf(TmdbApiException.class);
        server.verify();
    }

    @Test
    @DisplayName("getPopularMovies는 4xx 응답을 TmdbApiException으로 변환한다")
    void getPopularMovies_clientError_throwsTmdbApiException() {
        RestClient.Builder builder = newBuilder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TmdbApiClientImpl client = new TmdbApiClientImpl(builder.build());

        server.expect(requestTo("https://api.themoviedb.org/3/movie/popular?page=1"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED));

        assertThatThrownBy(() -> client.getPopularMovies(1))
                .isInstanceOf(TmdbApiException.class);
        server.verify();
    }

    @Test
    @DisplayName("getPopularMovies는 통신 실패(I/O 예외)를 TmdbApiException으로 변환한다")
    void getPopularMovies_ioException_throwsTmdbApiException() {
        RestClient.Builder builder = newBuilder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TmdbApiClientImpl client = new TmdbApiClientImpl(builder.build());

        server.expect(requestTo("https://api.themoviedb.org/3/movie/popular?page=1"))
                .andRespond(request -> {
                    throw new IOException("connection reset");
                });

        assertThatThrownBy(() -> client.getPopularMovies(1))
                .isInstanceOf(TmdbApiException.class);
        server.verify();
    }

    @Test
    @DisplayName("getPopularTvShows는 정상 응답을 파싱한다")
    void getPopularTvShows_success_parsesResponse() {
        RestClient.Builder builder = newBuilder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TmdbApiClientImpl client = new TmdbApiClientImpl(builder.build());

        String body = """
                {
                  "page": 1,
                  "total_pages": 500,
                  "results": [
                    {"id": 1, "name": "드라마", "overview": "설명",
                     "poster_path": "/path.jpg", "genre_ids": [18]}
                  ]
                }
                """;

        server.expect(requestTo("https://api.themoviedb.org/3/tv/popular?page=1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));

        TmdbPopularTvResponse response = client.getPopularTvShows(1);

        assertThat(response.results()).hasSize(1);
        assertThat(response.results().get(0).name()).isEqualTo("드라마");
        server.verify();
    }

    @Test
    @DisplayName("getPopularTvShows는 5xx 응답을 TmdbApiException으로 변환한다")
    void getPopularTvShows_serverError_throwsTmdbApiException() {
        RestClient.Builder builder = newBuilder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TmdbApiClientImpl client = new TmdbApiClientImpl(builder.build());

        server.expect(requestTo("https://api.themoviedb.org/3/tv/popular?page=1"))
                .andRespond(withServerError());

        assertThatThrownBy(() -> client.getPopularTvShows(1))
                .isInstanceOf(TmdbApiException.class);
        server.verify();
    }
}