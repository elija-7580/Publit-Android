# AMTAB Bari — licence inquiry (draft, not sent)

Draft email for AMTAB S.p.A. about redistributing the real-time GTFS export.
Italian first, English below. Do not send until you have reviewed the wording.

---

## Italiano

**Oggetto:** Licenza del feed GTFS in tempo reale e autorizzazione alla redistribuzione

Gentili signore e signori,

mi chiamo Elijá Friedrich-Ulrich. Sto sviluppando **Publit**, un’app open source per iPhone (MIT) che mostra orari e partenze in tempo reale tramite [Transitous](https://transitous.org), un servizio comunitario che aggrega feed GTFS aperti.

AMTAB pubblica già dati in tempo reale sulla pagina [OpenMobilityData](https://amtab.bari.it/it/openmobilitydata) (specifica tecnica: [Servizio_Export_GTFS.pdf](https://amtab.bari.it/images/Servizio_Export_GTFS.pdf)). Il GTFS statico di AMTAB è disponibile come open data del Comune di Bari (CC-BY). Per Transitous serve però il formato binario GTFS-Realtime (protobuf).

Ho preparato un convertitore open source nel repository Publit (`feeds/amtab-gtfsrt`) che trasforma l’export JSON di AMTAB in GTFS-RT protobuf, senza modificare i dati di viaggio. L’obiettivo è proporre questo feed a Transitous, così che tutte le app basate su Transitous (non solo Publit) possano mostrare ritardi e posizioni dei mezzi a Bari.

Vi chiedo cortesemente di chiarire:

1. **Con quale licenza** è pubblicato l’export in tempo reale?
2. È consentito **redistribuire** il feed convertito (protobuf) a Transitous e ad altri consumatori non commerciali, con attribuzione ad AMTAB?
3. Preferite un **contatto o un testo di attribuzione** specifico da visualizzare nell’app?

Resto a disposizione per qualsiasi dettaglio tecnico. Grazie per il vostro lavoro sui dati aperti della mobilità a Bari.

Cordiali saluti,  
Elijá Friedrich-Ulrich  
https://github.com/elija-7580/Publit-

---

## English

**Subject:** Licence of the real-time GTFS export and permission to redistribute

Dear AMTAB team,

my name is Elijá Friedrich-Ulrich. I am building **Publit**, a free open-source iPhone transit app (MIT) that uses [Transitous](https://transitous.org) for timetables and live departures.

AMTAB already publishes real-time data on [OpenMobilityData](https://amtab.bari.it/it/openmobilitydata) ([technical spec](https://amtab.bari.it/images/Servizio_Export_GTFS.pdf)). The static AMTAB GTFS is available as Comune di Bari open data (CC-BY). Transitous needs binary GTFS-Realtime (protobuf).

I have prepared an open-source converter in the Publit repository (`feeds/amtab-gtfsrt`) that turns AMTAB’s JSON export into GTFS-RT protobuf without changing trip data. The plan is to propose this feed to Transitous so every Transitous-based app—not only Publit—can show delays and vehicle positions in Bari.

Please could you clarify:

1. **Under which licence** is the real-time export published?
2. May the converted protobuf feed be **redistributed** to Transitous and other non-commercial consumers, with attribution to AMTAB?
3. Do you prefer a specific **contact or attribution text** to show in the app?

I am happy to share technical details. Thank you for your open mobility data work in Bari.

Kind regards,  
Elijá Friedrich-Ulrich  
https://github.com/elija-7580/Publit-
