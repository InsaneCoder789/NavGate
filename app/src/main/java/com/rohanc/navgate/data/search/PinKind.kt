package com.rohanc.navgate.data.search

/** What a place looks like on the map: each kind has its own icon and colour (see ui/map/MapIcons). Every category and subtype in the place indexes maps to one. */
enum class PinKind(val slug: String) {
    // Emergency and health
    Hospital("hospital"), Clinic("clinic"), Pharmacy("pharmacy"), Police("police"), Fire("fire"),
    // Eating and drinking
    Restaurant("restaurant"), Cafe("cafe"), Bakery("bakery"), Sweets("sweets"), Bar("bar"),
    // Money
    Bank("bank"), Atm("atm"),
    // Shopping
    Shop("shop"), Grocery("grocery"), Mall("mall"), Electronics("electronics"), Jewelry("jewelry"),
    // Getting around
    Fuel("fuel"), Charging("charging"), Parking("parking"), Transit("transit"), Airport("airport"), AutoService("autoservice"), Travel("travel"), Toilets("toilets"),
    // Staying and learning
    Hotel("hotel"), School("school"), College("college"), Coaching("coaching"), Library("library"),
    // Faith
    Temple("temple"), Mosque("mosque"), Church("church"), Worship("worship"),
    // Out and about
    Park("park"), Museum("museum"), Landmark("landmark"), Fitness("fitness"), Sports("sports"), Entertainment("entertainment"),
    // Services and offices
    Salon("salon"), Services("services"), Government("government"), Office("office"), RealEstate("realestate"),
    // Anything else with a name
    Other("other");

    /** The kinds that matter most in an emergency: always drawn, a little larger. */
    val urgent: Boolean get() = this == Hospital || this == Police || this == Fire

    companion object {
        /** The subtypes the Nearby sheet lists first, because they matter most in an emergency. */
        val EMERGENCY_SUBTYPES = listOf("hospital", "emergency department", "police station", "police", "fire station", "fire_station")

        private fun has(s: String, vararg words: String) = words.any { it in s }

        fun of(category: String, subtype: String): PinKind {
            val s = subtype.lowercase().replace('_', ' ')
            // What the place is called by its subtype wins over the broad category it was filed under.
            when {
                has(s, "police") -> return Police
                has(s, "fire station", "fire brigade") -> return Fire
                has(s, "hospital", "emergency") -> return Hospital
                has(s, "pharmac", "chemist", "drugstore") -> return Pharmacy
                has(s, "mosque", "masjid", "dargah") -> return Mosque
                has(s, "church", "cathedral", "chapel") -> return Church
                has(s, "hindu", "temple", "mandir", "shrine", "gurudwara", "gurdwara", "jain", "buddhist", "monastery") -> return if (has(s, "hindu", "temple", "mandir", "shrine")) Temple else Worship
                has(s, "airport", "airfield", "heliport") -> return Airport
                has(s, "charging", "ev ") -> return Charging
                has(s, "gas station", "petrol", "fuel") -> return Fuel
                has(s, "parking") -> return Parking
                has(s, "toilet", "restroom", "washroom") -> return Toilets
                has(s, "train station", "railway", "subway", "metro", "tram", "bus station", "bus stop", "bus terminal", "station", "ferry") && !has(s, "service station", "gas station", "police", "fire") && category != "fuel" -> return Transit
                has(s, "cafe", "coffee", "tea house", "tea room") -> return Cafe
                has(s, "bakery", "bakeries") -> return Bakery
                has(s, "ice cream", "dessert", "sweet", "candy", "confection", "chocolate", "mithai") -> return Sweets
                has(s, "bar", "pub", "lounge", "night club", "nightclub", "dance club", "brewery", "wine", "liquor") && !has(s, "barber") -> return Bar
                has(s, "atm") -> return Atm
                has(s, "bank", "credit union", "financial") -> return Bank
                has(s, "jewel", "diamond", "gold") -> return Jewelry
                has(s, "mobile phone", "electronics", "computer store", "computer hardware", "appliance", "camera store") -> return Electronics
                has(s, "shopping mall", "shopping centre", "shopping center", "department store", "hypermarket", "supercenter") -> return Mall
                has(s, "grocery", "supermarket", "convenience", "vegetable", "fruit", "butcher", "dairy", "market") -> return Grocery
                has(s, "auto dealer", "car dealer", "motorcycle", "auto parts", "tire", "automotive", "car repair", "auto repair", "car wash", "auto detailing", "mechanic", "garage", "vehicle") -> return AutoService
                has(s, "travel agen", "travel service", "tour", "travel and transportation", "visa", "ticket") -> return Travel
                has(s, "library") -> return Library
                has(s, "coaching", "tutoring", "tuition", "training", "computer coaching", "vocational", "driving school", "language school") -> return Coaching
                has(s, "college", "university", "institute", "academy") -> return College
                has(s, "school", "preschool", "kindergarten", "day care", "daycare", "education") -> return School
                has(s, "dentist", "dental", "clinic", "doctor", "diagnostic", "laborator", "physio", "optic", "optom", "eye care", "health", "medical", "nursing", "surgery", "dermatolog", "orthop", "gynec", "obstetric", "cardiolog", "pediatric", "therap", "ayurved", "naturopath", "homeopath", "veterinar") -> return Clinic
                has(s, "museum", "art gallery", "gallery", "aquarium", "zoo", "cultural") -> return Museum
                has(s, "monument", "historic", "fort", "memorial", "ruins", "viewpoint", "attraction", "heritage", "palace", "theme park", "beach") -> return Landmark
                has(s, "stadium", "cricket", "football", "sport", "swimming", "ground", "playground", "court", "golf", "arena") -> return Sports
                has(s, "gym", "fitness", "yoga", "dance studio", "martial", "pilates", "crossfit", "trainer") -> return Fitness
                has(s, "movie", "cinema", "theater", "theatre", "music venue", "event venue", "auditorium", "arcade", "amusement", "bowling", "fairground", "performing arts", "entertainment", "casino", "astrolog") -> return Entertainment
                has(s, "salon", "spa", "beauty", "barber", "hair", "nail", "tattoo", "massage", "makeup") -> return Salon
                has(s, "real estate", "property", "builder", "developer", "construction") -> return RealEstate
                has(s, "government", "municipal", "court house", "post office", "community centre", "community center", "public building", "embassy", "ward office", "tax") -> return Government
                has(s, "restaurant", "food", "dhaba", "eatery", "diner", "pizza", "burger", "biryani", "kitchen", "canteen", "mess", "snack", "caterer") -> return Restaurant
                has(s, "hotel", "resort", "hostel", "lodg", "guest house", "bed and breakfast", "motel", "inn", "apartment", "homestay", "campground", "villa") -> return Hotel
                has(s, "clothing", "shoe", "store", "shop", "boutique", "fashion", "gift", "furniture", "optical", "stationery", "bookstore", "toy", "florist", "hardware", "pet", "textile", "saree", "tailor") -> return Shop
            }
            return when (category) {
                "health" -> Clinic
                "pharmacy" -> Pharmacy
                "food" -> Restaurant
                "fuel" -> Fuel
                "ev" -> Charging
                "bank" -> Bank
                "atm" -> Atm
                "lodging" -> Hotel
                "shopping" -> Shop
                "grocery" -> Grocery
                "education" -> School
                "coaching" -> Coaching
                "library" -> Library
                "worship" -> Worship
                "park" -> Park
                "attraction" -> Landmark
                "fitness" -> Fitness
                "entertainment" -> Entertainment
                "transit" -> Transit
                "parking" -> Parking
                "toilets" -> Toilets
                "government" -> Government
                "services" -> Services
                "business" -> Office
                else -> Other
            }
        }
    }
}
