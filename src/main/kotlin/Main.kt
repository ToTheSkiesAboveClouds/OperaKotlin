import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

// =====================================================================
//  HOTEL RECEPTION — консольная система отеля глазами администратора
// =====================================================================

// ---------- ИНТЕРФЕЙС ----------
interface Cleanable {
    fun clean(): String
}

// ---------- НАСЛЕДОВАНИЕ + ПОЛИМОРФИЗМ ----------
abstract class Room(val number: Int, val floor: Int) : Cleanable {
    abstract val type: String
    abstract val pricePerNight: Int
    var isDirty = false

    override fun clean(): String {
        isDirty = false
        return "Номер $number убран"
    }

    open fun description(): String = "[$number] $type, этаж $floor, $pricePerNight ₸/ночь"
}

class StandardRoom(number: Int, floor: Int) : Room(number, floor) {
    override val type = "Standard"
    override val pricePerNight = 15_000
}

class DeluxeRoom(number: Int, floor: Int) : Room(number, floor) {
    override val type = "Deluxe"
    override val pricePerNight = 28_000
    override fun description() = super.description() + " (балкон)"
}

class Suite(number: Int, floor: Int) : Room(number, floor) {
    override val type = "Suite"
    override val pricePerNight = 50_000
    override fun clean(): String {
        isDirty = false
        return "Люкс $number: полная уборка + свежие цветы"
    }
    override fun description() = super.description() + " (гостиная, вид на горы)"
}

// ---------- DATA CLASSES ----------
data class Guest(val name: String, val phone: String, val isVip: Boolean = false)

data class Booking(
    val id: Int,
    val guest: Guest,
    val room: Room,
    val nights: Int,
    val orders: MutableList<ServiceOrder> = mutableListOf()
)

// ---------- SEALED CLASSES ----------
sealed class ServiceOrder {
    abstract val price: Int

    data class Food(val dish: String, override val price: Int) : ServiceOrder()
    data class Laundry(val items: Int) : ServiceOrder() {
        override val price: Int get() = items * 1_500
    }
    data class Taxi(val destination: String) : ServiceOrder() {
        override val price: Int get() = 4_000
    }
}

sealed class CheckInResult {
    data class Success(val booking: Booking) : CheckInResult()
    data class RoomBusy(val number: Int) : CheckInResult()
    data class RoomDirty(val number: Int) : CheckInResult()
    object NoSuchRoom : CheckInResult()
}

// ---------- MAP: меню рум-сервиса ----------
val foodMenu: Map<String, Int> = mapOf(
    "Бешбармак" to 6_500,
    "Цезарь с курицей" to 4_200,
    "Стейк рибай" to 11_000,
    "Чай с чак-чаком" to 1_800
)

// ---------- ОТЕЛЬ ----------
class Hotel(val name: String) {
    // LIST комнат
    val rooms: List<Room> = buildList {
        for (floor in 1..3) {
            for (i in 1..4) {
                val number = floor * 100 + i
                add(
                    when (floor) {
                        1 -> StandardRoom(number, floor)
                        2 -> DeluxeRoom(number, floor)
                        else -> Suite(number, floor)
                    }
                )
            }
        }
    }

    // MAP: номер комнаты -> активная бронь
    private val bookings = mutableMapOf<Int, Booking>()

    // LIST завершённых проживаний (для выручки)
    private val history = mutableListOf<Booking>()

    // SET: имена VIP-гостей (скидка 10%)
    private val vipNames = mutableSetOf<String>()

    private var nextId = 1

    // Функция высшего порядка: принимает лямбду-фильтр
    fun freeRooms(predicate: (Room) -> Boolean = { true }): List<Room> =
        rooms.filter { it.number !in bookings && !it.isDirty && predicate(it) }

    fun checkIn(roomNumber: Int, guest: Guest, nights: Int): CheckInResult {
        val room = rooms.find { it.number == roomNumber } ?: return CheckInResult.NoSuchRoom
        if (roomNumber in bookings) return CheckInResult.RoomBusy(roomNumber)
        if (room.isDirty) return CheckInResult.RoomDirty(roomNumber)
        if (guest.isVip) vipNames.add(guest.name)
        val booking = Booking(nextId++, guest, room, nights)
        bookings[roomNumber] = booking
        return CheckInResult.Success(booking)
    }

    fun addOrder(roomNumber: Int, order: ServiceOrder): Boolean {
        val booking = bookings[roomNumber] ?: return false
        booking.orders.add(order)
        return true
    }

    fun billFor(b: Booking): Int {
        val stay = b.room.pricePerNight * b.nights
        val services = b.orders.map { it.price }.fold(0) { acc, p -> acc + p }
        val total = stay + services
        return if (b.guest.name in vipNames) (total * 0.9).toInt() else total
    }

    fun checkOut(roomNumber: Int): Pair<Booking, Int>? {
        val booking = bookings.remove(roomNumber) ?: return null
        booking.room.isDirty = true
        history.add(booking)
        return booking to billFor(booking)
    }

    fun activeBookings(): List<Booking> = bookings.values.toList()
    fun dirtyRooms(): List<Room> = rooms.filter { it.isDirty }

    // reduce: общая выручка по выселенным гостям
    fun revenue(): Int =
        history.map { billFor(it) }.reduceOrNull { a, b -> a + b } ?: 0

    // groupBy + mapValues: загрузка по этажам
    fun occupancyByFloor(): Map<Int, String> =
        rooms.groupBy { it.floor }.mapValues { (_, list) ->
            "${list.count { it.number in bookings }}/${list.size}"
        }
}

// ---------- КОРУТИНЫ ----------
// suspend-функция: кухня / прачечная / такси работают не мгновенно
suspend fun prepareOrder(room: Int, order: ServiceOrder): String {
    val seconds = when (order) {
        is ServiceOrder.Food -> 1_500L
        is ServiceOrder.Laundry -> 2_000L
        is ServiceOrder.Taxi -> 1_000L
    }
    delay(seconds)
    return when (order) {
        is ServiceOrder.Food -> "Кухня: «${order.dish}» доставлен в номер $room"
        is ServiceOrder.Laundry -> "Прачечная: ${order.items} вещей из номера $room готовы"
        is ServiceOrder.Taxi -> "Такси до «${order.destination}» ждёт у входа (номер $room)"
    }
}

suspend fun housekeep(room: Room): String {
    delay(800L)
    return room.clean() // полиморфизм: у каждого типа номера своя уборка
}

// ---------- КОНСОЛЬ ----------
fun printMenu() {
    println(
        """
        
        ===== ЗАСЕЛЕНИЕ / РЕСЕПШН =====
        1. Свободные номера
        2. Заселить гостя
        3. Заказ услуги в номер
        4. Выселить гостя (счёт)
        5. Горничные: убрать грязные номера
        6. Текущие гости и статистика
        0. Выход
        """.trimIndent()
    )
    print("> ")
}

fun readInt(prompt: String): Int? {
    print(prompt)
    return readln().trim().toIntOrNull()
}

fun main() {
    val hotel = Hotel("Almaty Grand")

    // тестовые данные, чтобы сразу было что смотреть
    hotel.checkIn(101, Guest("Алихан Т.", "+7 701 000 00 01"), 2)
    hotel.checkIn(301, Guest("Мадина К.", "+7 702 000 00 02", isVip = true), 3)

    println("Добро пожаловать на ресепшн отеля ${hotel.name}!")

    while (true) {
        printMenu()
        when (readln().trim()) {
            "1" -> {
                val max = readInt("Максимальная цена за ночь (Enter — любая): ") ?: Int.MAX_VALUE
                val free = hotel.freeRooms { it.pricePerNight <= max } // лямбда
                if (free.isEmpty()) println("Свободных номеров нет") else free.forEach { println(it.description()) }
            }

            "2" -> {
                val number = readInt("Номер комнаты: ")
                if (number == null) { println("Неверный номер"); continue }
                print("Имя гостя: ")
                val name = readln().trim()
                print("Телефон: ")
                val phone = readln().trim()
                print("VIP? (y/n): ")
                val vip = readln().trim().lowercase() == "y"
                val nights = readInt("Сколько ночей: ") ?: 1

                // sealed class + when без else
                when (val result = hotel.checkIn(number, Guest(name, phone, vip), nights)) {
                    is CheckInResult.Success ->
                        println("Заселён: ${result.booking.guest.name} → ${result.booking.room.description()}")
                    is CheckInResult.RoomBusy -> println("Номер ${result.number} занят")
                    is CheckInResult.RoomDirty -> println("Номер ${result.number} ещё не убран")
                    CheckInResult.NoSuchRoom -> println("Такого номера нет в отеле")
                }
            }

            "3" -> {
                val room = readInt("Номер комнаты гостя: ")
                if (room == null) { println("Неверный номер"); continue }
                println("1. Еда  2. Прачечная  3. Такси")
                val order: ServiceOrder? = when (readInt("Выбор: ")) {
                    1 -> {
                        val dishes = foodMenu.entries.toList()
                        dishes.forEachIndexed { i, e -> println("${i + 1}. ${e.key} — ${e.value} ₸") }
                        val pick = readInt("Блюдо: ")?.let { dishes.getOrNull(it - 1) }
                        pick?.let { ServiceOrder.Food(it.key, it.value) }
                    }
                    2 -> readInt("Сколько вещей: ")?.let { ServiceOrder.Laundry(it) }
                    3 -> {
                        print("Куда: ")
                        ServiceOrder.Taxi(readln().trim())
                    }
                    else -> null
                }

                if (order == null) {
                    println("Неверный заказ")
                } else if (!hotel.addOrder(room, order)) {
                    println("В номере $room сейчас никто не проживает")
                } else {
                    println("Администратор: заказ принят, передаю в службу...")
                    // запускаем корутину и ждём результат
                    val message = runBlocking { async { prepareOrder(room, order) }.await() }
                    println(message)
                }
            }

            "4" -> {
                val room = readInt("Номер комнаты: ")
                if (room == null) { println("Неверный номер"); continue }
                val result = hotel.checkOut(room)
                if (result == null) {
                    println("В номере $room никто не живёт")
                } else {
                    val (booking, total) = result // деструктуризация
                    println("Выселен: ${booking.guest.name}")
                    println("Проживание: ${booking.nights} ноч. × ${booking.room.pricePerNight} ₸")
                    booking.orders.forEach { println("  + $it → ${it.price} ₸") }
                    if (booking.guest.isVip) println("  VIP-скидка 10%")
                    println("ИТОГО К ОПЛАТЕ: $total ₸")
                }
            }

            "5" -> {
                val dirty = hotel.dirtyRooms()
                if (dirty.isEmpty()) {
                    println("Все номера чистые")
                } else {
                    println("Горничные вышли на этаж (${dirty.size} номеров)...")
                    // все номера убираются параллельно
                    val reports = runBlocking { dirty.map { async { housekeep(it) } }.awaitAll() }
                    reports.forEach(::println)
                }
            }

            "6" -> {
                println("--- Проживают сейчас ---")
                val active = hotel.activeBookings()
                if (active.isEmpty()) println("Никого") else active.forEach {
                    val vip = if (it.guest.isVip) " ★VIP" else ""
                    println("№${it.room.number}: ${it.guest.name}$vip, ${it.nights} ноч., услуг: ${it.orders.size}")
                }
                println("--- Загрузка по этажам ---")
                hotel.occupancyByFloor().forEach { (floor, occ) -> println("Этаж $floor: $occ") }
                println("--- Выручка по выселенным: ${hotel.revenue()} ₸ ---")
            }

            "0" -> {
                println("Смена окончена. До свидания!")
                return
            }

            else -> println("Неизвестная команда")
        }
    }
}
