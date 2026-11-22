import { notFound } from "@tanstack/react-router";
import { isApiError } from "@/shared/lib/errors";

/**
 * Wraps what a route loader awaits in the app's error policy: an API 404 becomes `notFound()`, so the not-found page
 * renders inside the layout; everything else reaches the route's error component.
 */
export async function routeData<T>(load: Promise<T>): Promise<T> {
  try {
    return await load;
  } catch (error) {
    if (isApiError(error, 404)) throw notFound();
    throw error;
  }
}
