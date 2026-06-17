package ph.gov.geocamera.core.utils;

public class ProvinceLookup {

    static class Province {
        final String name;
        final double lat;
        final double lng;

        Province(String name, double lat, double lng) {
            this.name = name;
            this.lat = lat;
            this.lng = lng;
        }
    }

    private static final Province[] PROVINCES = new Province[]{

            new Province("Abra",17.5747,120.7441),
            new Province("Agusan del Norte",8.9456,125.5319),
            new Province("Agusan del Sur",8.4746,125.9596),
            new Province("Aklan",11.7000,122.3000),
            new Province("Albay",13.1775,123.5280),
            new Province("Antique",11.0833,122.0833),
            new Province("Apayao",18.0122,121.1710),
            new Province("Aurora",15.7550,121.5600),
            new Province("Basilan",6.4296,121.9870),
            new Province("Bataan",14.6417,120.4818),
            new Province("Batanes",20.4485,121.9708),
            new Province("Batangas",13.7565,121.0583),
            new Province("Benguet",16.4023,120.5960),
            new Province("Biliran",11.5833,124.4667),
            new Province("Bohol",9.8500,124.1435),
            new Province("Bukidnon",8.0515,125.0890),
            new Province("Bulacan",14.7942,120.8799),
            new Province("Cagayan",17.6131,121.7269),
            new Province("Camarines Norte",14.1390,122.7633),
            new Province("Camarines Sur",13.5250,123.3486),
            new Province("Camiguin",9.2043,124.7290),
            new Province("Capiz",11.5833,122.7500),
            new Province("Catanduanes",13.5986,124.1797),
            new Province("Cavite",14.4791,120.8970),
            new Province("Cebu",10.3157,123.8854),
            new Province("Cotabato",7.2047,124.2310),
            new Province("Davao de Oro",7.5600,126.1800),
            new Province("Davao del Norte",7.5611,125.6533),
            new Province("Davao del Sur",6.7663,125.3284),
            new Province("Davao Occidental",6.2150,125.0600),
            new Province("Davao Oriental",7.3172,126.5419),
            new Province("Dinagat Islands",10.1282,125.5977),
            new Province("Eastern Samar",11.5000,125.5000),
            new Province("Guimaras",10.5929,122.6325),
            new Province("Ifugao",16.8331,121.1710),
            new Province("Ilocos Norte",18.1647,120.7116),
            new Province("Ilocos Sur",17.2279,120.5739),
            new Province("Iloilo",10.7202,122.5621),
            new Province("Isabela",16.9754,121.8107),
            new Province("Kalinga",17.4740,121.3540),
            new Province("La Union",16.6159,120.3209),
            new Province("Laguna",14.1700,121.3330),
            new Province("Lanao del Norte",8.0000,124.3000),
            new Province("Lanao del Sur",7.8230,124.4198),
            new Province("Leyte",10.8625,124.8811),
            new Province("Maguindanao",6.9600,124.4200),
            new Province("Marinduque",13.4767,121.9032),
            new Province("Masbate",12.3667,123.5500),
            new Province("Misamis Occidental",8.3375,123.7071),
            new Province("Misamis Oriental",8.5046,124.6219),
            new Province("Mountain Province",17.1000,120.9000),
            new Province("Negros Occidental",10.2926,123.0247),
            new Province("Negros Oriental",9.7500,123.0000),
            new Province("Northern Samar",12.3613,124.7741),
            new Province("Nueva Ecija",15.5784,121.1113),
            new Province("Nueva Vizcaya",16.5415,121.2440),
            new Province("Occidental Mindoro",12.8797,121.7740),
            new Province("Oriental Mindoro",13.0565,121.4069),
            new Province("Palawan",9.8349,118.7384),
            new Province("Pampanga",15.0794,120.6200),
            new Province("Pangasinan",15.8949,120.2863),
            new Province("Quezon",13.8700,121.9100),
            new Province("Quirino",16.2700,121.6000),
            new Province("Rizal",14.6031,121.3084),
            new Province("Romblon",12.5778,122.2694),
            new Province("Samar",11.7800,125.0000),
            new Province("Sarangani",5.9267,125.0000),
            new Province("Siquijor",9.2000,123.5000),
            new Province("Sorsogon",12.9710,124.0056),
            new Province("South Cotabato",6.2700,124.8500),
            new Province("Southern Leyte",10.3346,125.1706),
            new Province("Sultan Kudarat",6.5500,124.5000),
            new Province("Sulu",6.0000,121.0000),
            new Province("Surigao del Norte",9.7845,125.4888),
            new Province("Surigao del Sur",8.5400,126.1100),
            new Province("Tarlac",15.4755,120.5963),
            new Province("Tawi-Tawi",5.2000,120.0000),
            new Province("Zambales",15.5082,119.9698),
            new Province("Zamboanga del Norte",8.3886,123.1689),
            new Province("Zamboanga del Sur",7.8383,123.2967),
            new Province("Zamboanga Sibugay",7.5225,122.3107)

    };

    public static String getNearestProvince(double lat, double lng) {

        Province nearest = null;
        double bestDist = Double.MAX_VALUE;

        for (Province p : PROVINCES) {

            double d =
                    (lat - p.lat) * (lat - p.lat) +
                            (lng - p.lng) * (lng - p.lng);

            if (d < bestDist) {
                bestDist = d;
                nearest = p;
            }
        }

        return nearest != null ? nearest.name : null;
    }
}