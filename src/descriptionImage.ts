import { descriptionLimits } from './itemDetails';

export async function prepareDescriptionImage(file: File) {
  if (!['image/jpeg', 'image/png', 'image/webp'].includes(file.type)) {
    throw new Error('Choose a JPEG, PNG, or WebP image.');
  }
  const source = URL.createObjectURL(file);
  try {
    const image = new Image();
    image.src = source;
    await image.decode();
    const scale = Math.min(1, 1600 / Math.max(image.naturalWidth, image.naturalHeight));
    const canvas = document.createElement('canvas');
    canvas.width = Math.max(1, Math.round(image.naturalWidth * scale));
    canvas.height = Math.max(1, Math.round(image.naturalHeight * scale));
    const context = canvas.getContext('2d');
    if (!context) throw new Error('This browser couldn’t prepare the image.');
    context.drawImage(image, 0, 0, canvas.width, canvas.height);
    for (const quality of [.82, .68, .52]) {
      const dataUrl = canvas.toDataURL('image/webp', quality);
      if (dataUrl.startsWith('data:image/webp;') && dataUrl.length <= Math.floor(descriptionLimits.imageSize / 3) * 4) return dataUrl;
    }
    if (file.size <= descriptionLimits.imageSize) {
      const reader = new FileReader();
      return await new Promise<string>((resolve, reject) => {
        reader.onload = () => resolve(String(reader.result));
        reader.onerror = reject;
        reader.readAsDataURL(file);
      });
    }
    throw new Error('This image is too large. Choose a smaller image.');
  } catch (error) {
    if (error instanceof DOMException) throw new Error('This image couldn’t be opened. Choose another image.');
    throw error;
  } finally { URL.revokeObjectURL(source); }
}
