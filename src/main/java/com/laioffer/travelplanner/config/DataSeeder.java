package com.laioffer.travelplanner.config;

import com.laioffer.travelplanner.entity.City;
import com.laioffer.travelplanner.entity.Poi;
import com.laioffer.travelplanner.repository.CityRepository;
import com.laioffer.travelplanner.repository.PoiRepository;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.ArrayList;
import java.util.List;

/**
 * Seeds the searchable POI catalog — three cities and the places in them. No accounts and no trips:
 * those are created by whoever uses the app.
 *
 * <p>POIs live in our own database on purpose: the brief asks for database-backed search, and it also
 * keeps the Places API out of the critical path (and off the bill). Google Maps is used for what it is
 * uniquely good at — rendering, overlays and routing.
 */
@Configuration
@Profile("demo-seed")
@ConditionalOnProperty(prefix = "travelplanner.seed", name = "enabled", havingValue = "true")
public class DataSeeder {

    @Bean
    public ApplicationRunner seed(CityRepository cityRepository, PoiRepository poiRepository) {
        return args -> {
            City tokyo = cityRepository.findByNameAndCountry("Tokyo", "Japan")
                    .orElseGet(() -> cityRepository.save(new City("Tokyo", "Japan", "Asia/Tokyo", 35.6812, 139.7671, 12, "🗼")));
            City sf = cityRepository.findByNameAndCountry("San Francisco", "USA")
                    .orElseGet(() -> cityRepository.save(new City("San Francisco", "USA", "America/Los_Angeles", 37.7749, -122.4194, 12, "🌉")));
            City paris = cityRepository.findByNameAndCountry("Paris", "France")
                    .orElseGet(() -> cityRepository.save(new City("Paris", "France", "Europe/Paris", 48.8566, 2.3522, 12, "🥐")));

            savePois(poiRepository, tokyo, TOKYO);
            savePois(poiRepository, sf, SAN_FRANCISCO);
            savePois(poiRepository, paris, PARIS);
        };
    }

    private void savePois(PoiRepository repository, City city, String[] rows) {
        List<Poi> pois = new ArrayList<>(rows.length);
        for (String row : rows) {
            String[] f = row.split("\\|");
            if (!repository.existsByCityIdAndName(city.getId(), f[0])) {
                pois.add(new Poi(city, f[0], f[1], Double.parseDouble(f[2]), Double.parseDouble(f[3]),
                        Double.parseDouble(f[4]), Integer.parseInt(f[5]), Integer.parseInt(f[6]),
                        Integer.parseInt(f[7]), f[8]));
            }
        }
        if (!pois.isEmpty()) {
            repository.saveAll(pois);
        }
    }

    // name | category | lat | lng | rating | visitMinutes | openHour | closeHour | description

    private static final String[] TOKYO = {
            "Senso-ji Temple|Temple|35.7148|139.7967|4.6|60|6|17|Tokyo's oldest temple, with the Nakamise shopping street leading up to it",
            "Tokyo Skytree|Viewpoint|35.7101|139.8107|4.5|90|9|21|634 m tower; the Tembo Deck looks straight down the Sumida river",
            "Shibuya Crossing|Landmark|35.6595|139.7004|4.5|30|0|24|The world's busiest pedestrian scramble, best at dusk",
            "Meiji Jingu|Temple|35.6764|139.6993|4.6|60|5|18|Forest shrine in the middle of the city, a short walk from Harajuku",
            "Harajuku Takeshita Street|Shopping|35.6702|139.7027|4.2|60|10|20|Crepes, thrift shops and street fashion in 350 crowded metres",
            "Tokyo Tower|Viewpoint|35.6586|139.7454|4.4|75|9|22|The 1958 red lattice tower, still the friendliest skyline view",
            "Imperial Palace East Gardens|Park|35.6852|139.7528|4.4|75|9|17|Stone walls and moats of Edo Castle, free to wander",
            "Tsukiji Outer Market|Food|35.6654|139.7707|4.4|90|5|14|Tamagoyaki, uni and knife shops — go hungry and go early",
            "teamLab Planets|Museum|35.6487|139.7864|4.5|120|9|21|Barefoot digital art; you walk through water and mirrors",
            "Ueno Park|Park|35.7148|139.7737|4.4|90|5|23|Museum cluster, a pond full of lotus, and Tokyo's best cherry blossoms",
            "Tokyo National Museum|Museum|35.7188|139.7766|4.5|120|9|17|Japan's oldest museum; the samurai armour hall alone is worth it",
            "Akihabara Electric Town|Shopping|35.6987|139.7730|4.4|90|10|20|Eight floors of anime, retro games and component shops",
            "Shinjuku Gyoen|Park|35.6852|139.7100|4.6|75|9|18|Three gardens in one, with skyscrapers over the treeline",
            "Omoide Yokocho|Nightlife|35.6936|139.6997|4.3|75|17|23|Smoky yakitori alley beside Shinjuku station",
            "Golden Gai|Nightlife|35.6938|139.7048|4.3|90|18|24|Six lanes of tiny bars, most seating six people at a time",
            "Tokyo Metropolitan Observatory|Viewpoint|35.6896|139.6917|4.4|45|9|22|Free 202 m observation deck in Shinjuku",
            "Ginza Chuo-dori|Shopping|35.6717|139.7650|4.4|90|10|20|Flagship stores and department-store food halls; car-free on weekends",
            "Zojo-ji Temple|Temple|35.6574|139.7480|4.3|45|9|17|Tokugawa family temple framed by Tokyo Tower",
            "Mori Art Museum|Museum|35.6606|139.7298|4.4|90|10|22|Contemporary art on the 53rd floor, open late",
            "Shibuya Sky|Viewpoint|35.6580|139.7016|4.6|60|10|22|Open-air rooftop 230 m above the crossing; sunset slots sell out",
            "Yanaka Ginza|Food|35.7276|139.7660|4.3|60|10|18|Old-Tokyo shopping street full of cats and croquettes",
            "Ryogoku Kokugikan|Landmark|35.6970|139.7933|4.3|60|9|17|The national sumo stadium and its small sumo museum",
            "Odaiba Seaside Park|Park|35.6297|139.7736|4.4|75|0|24|Bay-front boardwalk looking back at the Rainbow Bridge",
            "Toyosu Market|Food|35.6459|139.7864|4.3|90|5|14|The tuna auction's new home, with sushi breakfast upstairs",
            "Nezu Shrine|Temple|35.7203|139.7580|4.4|40|6|17|Tunnel of vermilion gates and an azalea garden",
            "Kagurazaka|Food|35.7018|139.7402|4.2|75|11|22|Cobbled slopes of French bistros and hidden ryotei",
            "Nakameguro Canal|Park|35.6440|139.6989|4.4|60|0|24|Cherry-lined canal with coffee roasters and small book shops",
            "Ghibli Museum|Museum|35.6962|139.5704|4.7|120|10|18|Totoro's own museum in Mitaka; tickets must be booked ahead",
            "Sumida Park|Park|35.7130|139.8010|4.3|45|0|24|Riverside path with the classic Skytree photo spot",
            "Kanda Myojin|Temple|35.7020|139.7677|4.4|40|9|17|Shrine where Akihabara comes to bless its laptops",
    };

    private static final String[] SAN_FRANCISCO = {
            "Golden Gate Bridge Welcome Center|Landmark|37.8078|-122.4750|4.8|60|9|18|Walk out onto the south tower span; fog optional but likely",
            "Palace of Fine Arts|Landmark|37.8029|-122.4485|4.7|45|0|24|Beaux-Arts rotunda left over from the 1915 world's fair",
            "Fisherman's Wharf|Landmark|37.8080|-122.4177|4.3|75|9|22|Clam chowder, street performers and sourdough bread bowls",
            "Pier 39 Sea Lions|Landmark|37.8087|-122.4098|4.5|60|9|22|A few hundred barking sea lions who moved in and never left",
            "Alcatraz Landing|Landmark|37.8095|-122.4150|4.7|180|9|17|Ferry and audio tour of the island prison; book weeks ahead",
            "Ferry Building Marketplace|Food|37.7955|-122.3937|4.6|75|10|18|Oysters, Blue Bottle and a farmers market three days a week",
            "Chinatown Dragon Gate|Landmark|37.7908|-122.4056|4.4|60|0|24|Entrance to the oldest Chinatown in North America",
            "Lombard Street|Landmark|37.8021|-122.4187|4.4|30|0|24|Eight hairpin turns down a hydrangea-lined block",
            "Coit Tower|Viewpoint|37.8024|-122.4058|4.5|45|10|17|Depression-era murals inside, 360° bay views on top",
            "Exploratorium|Museum|37.8017|-122.3973|4.6|150|10|17|Hands-on science museum on the waterfront piers",
            "SFMOMA|Museum|37.7857|-122.4011|4.6|120|10|17|Seven floors of modern art and a living wall",
            "Union Square|Shopping|37.7880|-122.4075|4.3|60|10|20|Department stores, cable car turntable one block away",
            "Conservatory of Flowers|Park|37.7726|-122.4602|4.6|60|10|18|Victorian glasshouse of orchids and carnivorous plants",
            "de Young Museum|Museum|37.7715|-122.4687|4.6|105|9|17|American art plus a free observation tower",
            "California Academy of Sciences|Museum|37.7699|-122.4661|4.6|150|9|17|Aquarium, planetarium and rainforest under a living roof",
            "Japanese Tea Garden|Park|37.7702|-122.4703|4.6|45|9|18|Oldest public Japanese garden in the US; go right at opening",
            "Twin Peaks|Viewpoint|37.7544|-122.4477|4.7|45|0|24|The postcard view down Market Street; windy every day",
            "Mission Dolores Park|Park|37.7596|-122.4269|4.6|60|6|22|Sunny slope where the whole city picnics",
            "Painted Ladies|Landmark|37.7763|-122.4324|4.6|30|5|22|Victorian row on Alamo Square with the skyline behind",
            "Haight-Ashbury|Shopping|37.7699|-122.4469|4.2|60|10|20|Record stores and vintage racks at the 1967 epicentre",
            "Ghirardelli Square|Food|37.8058|-122.4229|4.4|60|10|21|Old chocolate factory turned sundae destination",
            "Baker Beach|Park|37.7936|-122.4836|4.6|60|6|21|Cold water, warm sand, and the bridge straight ahead",
            "Presidio Tunnel Tops|Park|37.8016|-122.4664|4.7|60|6|22|New parkland built over the freeway, with food trucks",
            "Oracle Park|Landmark|37.7786|-122.3893|4.7|120|9|21|Bay-side ballpark; garlic fries are non-negotiable",
            "Castro Theatre|Nightlife|37.7620|-122.4348|4.5|60|17|23|1922 movie palace with a Wurlitzer organ before screenings",
            "North Beach Cafes|Food|37.8000|-122.4090|4.4|75|8|22|Italian espresso bars and City Lights bookstore",
            "Lands End Trail|Park|37.7800|-122.5110|4.7|90|6|20|Cliff path past shipwrecks and the Sutro Baths ruins",
            "Powell Street Cable Car Turnaround|Landmark|37.7850|-122.4079|4.4|45|7|22|Where they spin the cars by hand; queue early",
    };

    private static final String[] PARIS = {
            "Eiffel Tower|Landmark|48.8584|2.2945|4.7|120|9|23|Book the summit; the second floor has the better view",
            "Louvre Museum|Museum|48.8606|2.3376|4.7|180|9|18|Pick three wings and accept you cannot see it all",
            "Notre-Dame Cathedral|Temple|48.8530|2.3499|4.7|45|8|18|Reopened Gothic icon on the Ile de la Cite",
            "Sainte-Chapelle|Temple|48.8554|2.3450|4.7|60|9|17|Fifteen stained-glass walls; go on a bright afternoon",
            "Musee d'Orsay|Museum|48.8600|2.3266|4.7|120|9|18|Impressionists in a converted railway station",
            "Arc de Triomphe|Landmark|48.8738|2.2950|4.7|60|10|22|Rooftop looks straight down twelve avenues",
            "Champs-Elysees|Shopping|48.8698|2.3078|4.4|75|10|20|Two kilometres of flagship stores and cafe terraces",
            "Sacre-Coeur|Temple|48.8867|2.3431|4.7|60|6|22|White basilica on the highest point in Paris",
            "Place du Tertre|Landmark|48.8865|2.3406|4.4|60|0|24|Montmartre's painters' square, tourist-thick but charming",
            "Luxembourg Gardens|Park|48.8462|2.3372|4.7|75|8|20|Green chairs, a fountain, and toy sailboats",
            "Palais Garnier|Landmark|48.8720|2.3316|4.6|75|10|17|The opera house that inspired the Phantom",
            "Centre Pompidou|Museum|48.8607|2.3522|4.4|105|11|21|Modern art behind exposed pipes; top-floor terrace view",
            "Le Marais|Shopping|48.8590|2.3600|4.5|90|10|19|Falafel, concept stores and the Place des Vosges",
            "Place de la Bastille|Landmark|48.8532|2.3692|4.2|30|0|24|Where the prison stood; now a busy roundabout and opera",
            "Seine Cruise Dock|Landmark|48.8600|2.2977|4.5|75|10|22|One-hour boat loop past every monument on the water",
            "Trocadero Gardens|Viewpoint|48.8620|2.2885|4.6|45|0|24|The Eiffel Tower photograph everyone means",
            "Rodin Museum|Museum|48.8553|2.3158|4.6|90|10|18|The Thinker in a rose garden; a calm afternoon",
            "Saint-Germain Cafes|Food|48.8540|2.3336|4.4|75|8|23|Flore, Deux Magots, and a very slow coffee",
            "Rue Mouffetard Market|Food|48.8422|2.3495|4.5|60|8|19|Cheese, bread and oysters down a sloping market street",
            "Pantheon|Landmark|48.8462|2.3464|4.6|60|10|18|Crypt of Curie, Hugo and Zola, plus a dome climb",
            "Galeries Lafayette Rooftop|Viewpoint|48.8735|2.3320|4.5|45|10|20|Free rooftop terrace over the Opera district",
            "Canal Saint-Martin|Park|48.8710|2.3660|4.4|60|0|24|Iron footbridges, picnics and neighbourhood bars",
            "Pere Lachaise Cemetery|Park|48.8614|2.3933|4.5|90|8|18|Cobbled avenues of famous graves under old chestnuts",
            "Tuileries Garden|Park|48.8634|2.3275|4.6|60|7|21|Formal alleys between the Louvre and Concorde",
            "Moulin Rouge|Nightlife|48.8841|2.3322|4.3|120|19|24|The original cabaret; dinner shows book out fast",
            "Ile Saint-Louis|Food|48.8517|2.3570|4.6|60|0|24|Berthillon ice cream and the quietest quays in Paris",
    };
}
