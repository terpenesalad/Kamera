package app.kamera.render

import app.kamera.film.ColorGrade
import app.kamera.film.FilmStock
import app.kamera.film.LutBaker
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/** Process-wide memory of recently baked cubes, so switching film feels instantaneous. */
object LutStore {
    private data class Key(val stockId: String, val grade: ColorGrade)

    private val cache = object : LinkedHashMap<Key, IntArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, IntArray>?): Boolean = size > 18
    }

    fun get(stock: FilmStock, grade: ColorGrade): IntArray {
        val key = Key(stock.id, grade)
        synchronized(cache) { cache[key] }?.let { return it }
        val cube = LutBaker.bake(stock, grade)
        synchronized(cache) { cache[key] = cube }
        return cube
    }
}

/** Bakes cubes off the render thread; when requests pile up only the newest one is honoured. */
class LutBakeQueue(private val onBaked: (IntArray) -> Unit) {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "LutBake").apply { isDaemon = true } }
    private val pending = AtomicReference<Pair<FilmStock, ColorGrade>?>(null)

    fun request(stock: FilmStock, grade: ColorGrade) {
        if (pending.getAndSet(stock to grade) != null) return
        executor.execute {
            val request = pending.getAndSet(null) ?: return@execute
            onBaked(LutStore.get(request.first, request.second))
        }
    }

    fun shutdown() = executor.shutdownNow()
}
