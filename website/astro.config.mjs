import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';

export default defineConfig({
  site: 'https://properpcloud.fkr.dev',
  output: 'static',
  integrations: [
    starlight({
      title: 'properpcloud',
      description: 'Folder-first pCloud audio playback on Android and Linux.',
      logo: {
        src: './src/assets/logo.png',
        alt: 'properpcloud badger and cloud logo',
      },
      favicon: '/favicon.png',
      customCss: ['./src/styles/custom.css'],
      social: [
        {
          icon: 'github',
          label: 'GitHub',
          href: 'https://github.com/fkr-0/properpcloud',
        },
      ],
      editLink: {
        baseUrl: 'https://github.com/fkr-0/properpcloud/edit/main/docs/',
      },
      sidebar: [
        { label: 'Overview', items: [{ label: 'Home', slug: '' }] },
        { label: 'User manual', items: [{ autogenerate: { directory: 'user-manual' } }] },
        { label: 'Developer guide', items: [{ autogenerate: { directory: 'development' } }] },
        { label: 'API reference', items: [{ autogenerate: { directory: 'api' } }] },
        {
          label: 'Architecture and policy',
          items: [
            { label: 'Architecture', slug: 'architecture' },
            { label: 'Linux client', slug: 'linux-client' },
            { label: 'Privacy', slug: 'privacy' },
            { label: 'Verification', slug: 'verification' },
            { label: 'Roadmap', slug: 'roadmap' },
          ],
        },
      ],
    }),
  ],
});
