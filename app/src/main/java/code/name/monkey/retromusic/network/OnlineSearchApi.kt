package code.name.monkey.retromusic.network

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

interface SoundCloudApi {
    @GET("s/{query}")
    suspend fun search(@Path("query") query: String): ScSearchResponse

    @GET("d/{user}/{track}")
    suspend fun getStream(
        @Path("user") user: String,
        @Path("track") track: String
    ): ScStreamResponse
}

interface SpotifyApi {
    @GET("s/{query}")
    suspend fun search(@Path("query") query: String): List<SpTrack>

    @Streaming
    @GET("d/{id}")
    suspend fun downloadTrack(@Path("id") id: String): Response<ResponseBody>
}

interface LrcLibApi {
    @GET("api/get")
    suspend fun getLyrics(
        @Query("artist_name") artistName: String,
        @Query("track_name") trackName: String
    ): LrcLibResponse
}

object OnlineSearchApiProvider {
    val soundCloud: SoundCloudApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://0.4texasplayz4.workers.dev/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SoundCloudApi::class.java)
    }

    val spotify: SpotifyApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://spotify.4texasplayz4.workers.dev/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(SpotifyApi::class.java)
    }

    val lrcLib: LrcLibApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://lrclib.net/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(LrcLibApi::class.java)
    }
}
