export type SearchResult = {
  name: string;
  lat: number;
  lon: number;
  display: string;
};

export class AutocompleteService {

  async search(query: string): Promise<SearchResult[]> {
    if (query.length < 2) return [];

    try {
      const res = await fetch(
        `https://nominatim.openstreetmap.org/search?q=${encodeURIComponent(
          query
        )}&format=json&limit=5&countrycodes=in&viewbox=72,8,88,23&bounded=1`
      );

      const data = await res.json();

      return data
        .filter((item: any) => {
          const display = item.display_name.toLowerCase();

          return (
            display.includes("bhubaneswar") ||
            display.includes("mumbai")
          );
        })
        .map((item: any) => ({
          name: item.display_name,
          lat: parseFloat(item.lat),
          lon: parseFloat(item.lon),
          display: item.display_name,
        }));

    } catch (err) {
      console.log("Autocomplete error:", err);
      return [];
    }
  }
}