export const BASE_PATH = "/api/v1".replace(/\/+$/, "");

export interface ApiConfig {
    basePath?: string;
    apiKey?: string;
}

export function exists(json: any, key: string) {
    return json !== null && json !== undefined && typeof json === 'object' && key in json;
}
