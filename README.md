# Röda

Röda on uusi ohjelmointikieleni! Se perustuu syntaksiltaan ja ominaisuuksiltaan sh-skripteihin.

## Perustietoja

Röda-funktiolla on parametrit, sekä sisään- ja ulostulo, jotka ovat jonoja. Funktio ei varsinaisesti
"palauta" mitään arvoa, vaan funktion paluuarvo voidaan ajatella listana kaikista sen ulostulovirran
arvoista. Funktio voi lukea sisääntuloaan `pull`-komennolla ja työntää ulostuloon arvoja `push`-komennolla.

`main`-funktion sisään- ja ulostulo on kytketty standardisyötteeseen ja -tulosteeseen.

Alla on yksinkertainen duplicate-funktio, joka lukee yhden arvon ja palauttaa kaksi arvoa. Koska sitä
kutsutaan `main`-funktiosta, lukee se syötettä suoraan standardisyötteestä.
```
duplicate {
     pull value
     push value
     push value
}

main {
     push "Syötä tekstiä: "
     duplicate
}
```

Funktioita voi putkittaa `|`-operaattorilla, jolloin niiden sisään- ja ulostulovirrat kytketään toisiinsa.
Seuraava ohjelma tulostaa kahtena tiedoston "tieto.txt" ensimmäisen rivin:

```
cat "tieto.txt" | duplicate
```

Jos kaikki rivit haluttaisiin kahdentaa, olisi tehtävä uusi versio duplicate-funktiosta, joka lukee
kaiken mahdollisen syötteen:

```
duplicate {
    while pull -r value; do
        push value
	push value
    done
}
```

`pull -r` (**r**esponse) palauttaa `true`n tai `false`n riippuen siitä, onko luettavaa vielä jäljellä.

## Perussyntaksi

### Rivinvaihdot

Joissakin kohdissa on oltava rivinvaihto.

- Lauseet erotellaan toisistaan rivinvaihdoilla.
- `for`, `while` ja `if` vaativat rivinvaihdon ennen koodilohkoa.
- Nimettömän funktion parametrilistan jälkeen on oltava rivinvaihto.

Rivinvaihtojen tilalla voi käyttää `;`-merkkiä ja toisinpäin.

### Kommentit

Röda käyttää C-tyylisiä `/* ... */`-kommentteja.

### Funktiot

Röda-ohjelma on joukko funktioita:
```
funktio1 parametrit {

}

funktio2 parametrit {

}
```

Funktiolla voi ottaa vaihtelevan määrän argumentteja, jolloin viimeiselle parametrille pitää antaa
`...`-määrite:

```
duplicate_files files... {
    for file in files; do
        cat file | duplicate
    done
}
```

Funktio voi ottaa myös muuttujaviittauksen, jolloin ko. parametrille pitää antaa `&`-määrite. Seuraava funktio
lukee kaksi arvoa, tekee niistä listan ja asettaa sen muuttujaan.

```
pull_twice &variable {
    pull value1
    pull value2
    variable -set (value1 value2)
}
```

### Lauseet

Funktioiden sisällä on lauseita, jotka koostuvat yhdestä tai useammasta putkitetusta komennosta.
Putki yhdistää komentojen ulos- ja sisääntulot toisiinsa. Lauseen ensimmäisen ja viimeisen komennon
sisään- ja ulostulo on kytketty isäntäfunktion sisään- ja ulostuloon.

```
komento1 argumentit | komento2 argumentit | komento3 argumentit
```

### Komennot

Komento voi olla joko funktiokutsu tai ohjausrakenne.

#### Funktiokutsu

Funktiokutsu koostuu funktiosta ja argumenteista, joita ei ole erikseen eroteltu toisistaan.
Myös muitakin arvoja kuin funktioita voidaan kutsua ikään, kuin ne olisivat funktioita. Nämä
erityistapaukset on alempana.

Funktion argumentteina mahdollisesti olevat funktiokutsut on kytketty isäntäfunktion virtaan, eikä
putkeen. Vain kutsuttava funktio putkittuu.

#### Eri arvojen "kutsuminen"

##### Muuttujat

Muuttujaa voi "kutsua" usealla eri tavalla.

Uuden muuttujan voi luoda lipulla `-create`:
```
tiedosto -create "tieto.txt"
ikä -create 73
tytöt -create ("Annamari" "Reetta" "Vilma")
```

Muuttujalle voi asettaa uuden arvon lipulla `-set`:
```
ikä -set 74
```

Listaan voi lisätä arvon lipulla `-add`:
```
tytöt -add "Maija"
```

Listan kohtaa voi muuttaa lipulla `-put`:
```
tytöt -put 1 "Liisa"
```

Merkkijonon perään voi lisätä tekstiä lipulla `-append`:
```
nimi -create etunimi
nimi -append " "..sukunimi
```

Lukua voi kasvattaa tai vähentää lipuilla `-inc` ja `-dec`:
```
ikä -inc
voimat -dec
```

Ilman argumentteja muuttuja työntää oman arvonsa ulostulovirtaansa. Näin muuttujan arvoa voi helposti putkittaa:
```
tarina -create "hänen nimensä oli Mark ja syntymävuotensa 2014"
tarina | grep "[0-9]+" /* etsii syntymävuoden */
```

Jos muuttujan arvo on kuitenkin lista, työnnetään jokainen alkio yksitellen (ks. alla).
Jos muuttujan haluaa työntää varmasti sellaisenaan, voi käyttää komentoa `push`:
```
push muuttuja | komento
```

##### Listat

Listan "kutsuminen" työntää kaikki listan alkiot ulostulovirtaan:
```
("rivi1\n" "rivi2\n" "rivi3\n") | write tiedosto
```

#### Ohjausrakenteet

Ohjausrakenteita ovat `if`, `while`, `for` ja `try`.

**`if`** ja **`while`** suorittavat annetun lauseen ja olettavat sen palauttavan joko arvon `true` tai arvon `false`.
Muut arvot tulkitaan aina samoin kuin `true`. Vain yksi arvo luetaan.

Sisäänrakennetuista funktioista vain `true`, `false`, `test` (ks. alempana) ja `pull -r` palauttavat totuusarvon.

```
if test ikä -lt 18; do
    pull "Olet liian nuori!\n"
done
```

```
while test vastaus -not-matches "kyllä|ei"; do
    push "Vastaa kyllä tai ei: "
    pull vastaus
done
```

**`for`** käy läpi annetun listan kaikki arvot:

```
tytöt -create (("Annamari" 1996) ("Reetta" 1992) ("Vilma" 1999))
for tyttö in tytöt; do
    push "Hänen nimensä on "..tyttö[0].." ja hän on syntynyt vuonna "..tyttö[1].."\n"
done
```

**`try`** suorittaa annetun komennon tai lohkon ja ohittaa hiljaisesti kaikki vastaan tulleet virheet.
Aion ehkä tehdä jonkinlaisen virheenkäsittelytoiminnon siihen tulevaisuudessa. Tällä hetkellä tätä
kannattaa käyttää vain palvelimissa ym. joissa prosessin on pysyttävä virheistä huolimatta päällä.

```
while true; do
    try do
        hae viestit
	käsittele viestit
    done
done
```

### Lausekkeet

Funktion argumentit (ja kutsuttava funktio itsekin) ovat lausekkeita. Lauseke voi olla joko muuttuja,
luku, merkkijono, lippu, lista, komento tai nimetön funktio. Lisäksi Rödassa on muutama operaattori
merkkijonojen ja listojen käsittelemiseen. Aritmeettisia operaattoreita ei vielä ole.

#### Luvut, merkkijonot ja liput

Kaikki funktiot hyväksyvät lukujen tilalla merkkijonoja (joiden toki pitää sisältää vain lukuja)
ja merkkijonojen tilalla lukuja. Optimointisyistä on kuitenkin aina hyvä käyttää lukuliteraaleja kaikkialla,
missä mahdollista.

Viiva-merkki (`-`) aloittaa lippuliteraalin. Liput ovat syntaksisokeria merkkijonoille. `-lippu` ja `"-lippu"`
ovat sama asia.

Merkkijonoja voi yhdistellä `..`-operaattorilla ja niiden pituuden voi saada `#`-operaattorilla.

```
nimi -create etunimi.." "..sukunimi
push "Nimesi pituus on " #nimi "\n"
```

#### Listat

Listaliteraali on joukko sulkujen ympäröimiä arvoja, joita ei ole mitenkään erikseen eroteltu toisistaan.

Listasta voi hakea yksittäisiä alkioita `[]`-operaattorilla. Lisäksi osalistoja voi luota `[:]`-operaattorilla.
Kuten merkkijonoillakin, `#` palauttaa listan koon.

Kaikki listan alkiot voi yhdistää merkkijonoksi `&`-operaattorilla, jonka toinen operandi on merkkijono, joka
pistetään alkioiden väleihin.

```
tytöt -create ("Annamari" "Reetta" "Vilma" "Susanna")
push "Tyttöjä on " #tytöt " kpl.\n"
push "Ensimmäinen tyttö on " tytöt[0] " ja viimeinen " tytöt[-1] ". "
push "Välissä ovat " tytöt[1:-1]&" ja " ".\n"
```

Jos listaan yhdistää merkkijonon, yhdistetään se kaikkiin listan alkioihin:

```
sukunimi -create "Kivinen"
sisarukset -create ("Maija" "Ilmari")
kokonimet -create sisarukset.." "..sukunimi
push "Sisarusten koko nimet ovat " kokonimet&" ja " ".\n"
```

#### Komento

Lauseke voi olla myös komento tai putkitettuja komentoja. Tälloin lausekkeen arvoksi tulee lista,
joka on muodostettu kaikista viimeisen komennon ulostulon antamista arvoista.

Seuraava ohjelma tulostaa tiedoston rivinumeroiden kera.

```
rivit -create !(cat tiedosto)
i -create 1
for rivi in rivit; do
    push i " " rivi "\n"
    i -inc
done
```

#### Nimetön funktio

Nimetön funktio toimii kuten tavallinenkin funktio. Itse asiassa, jos muuttujaan asettaa nimettömän funktion arvon,
se ei ole enää muuttuja vaan alkaa käyttäytyä kuin tavallinen funktio. Siispä esimerkiksi lippu `-set` ei enää
toimi. Nimettömien funktioiden kanssa kannataakin olla varovainen. Syntaksi on `{ |parametrit|; koodi }`.

Seuraavassa koodissa määritellään `filter`-funktio, joka lukee arvoja ja palauttaa osan niistä.

```
filter cond_function {
    while pull -r value; do
        if cond_function value; do
	    push value
	done
    done
}
```

Funktiota käytetään antamalla sille nimetön funktio (tai tavallinenkin funktio käy), joka palauttaa
`true`n tai `false`n.

```
tytöt -create (("Annamari" 1996) ("Reetta" 1992) ("Vilma" 1999))
tytöt | filter { |tyttö|; test tyttö[1] -gt 1995 } | while pull -r tyttö; do
    push tyttö " on vielä nuori.\n"
done
```

## Lista sisäänrakennetuista funktioista

Tässä listassa sulkuja `()` käytetään ryhmittelemiseen, `[]` valinnaisuuteen ja merkkiä `|` vaihtoehtoon.
Merkki `*` tarkoittaa "nolla tai useampi" ja `+` yksi tai useampi.

### push

`push arvo+`

Työntää arvot ulostulovirtaan.

### pull

`pull [-r] muuttuja+`

Lukee muuttujaan arvon sisääntulovirrasta. Jos valitsin `-r` on käytössä, palautetaan jokaista onnistunutta
lukua kohti ulostulovirtaan arvo `true` ja jokaista epäonnistunutta lukua kohti arvo `false`.

### seq

`seq alku loppu`

Palauttaa kokonaisluvut välillä `[alku, loppu]`.

### grep

`grep [-o] regex`

Lukee sisääntulovirrasta merkkijonoarvoja. Jos merkkijono täsmää annettuun säännölliseen lausekkeeseen, se työnnetään
ulostulovirtaan. Jos valitsinta `-o` (**o**nly-matching) on käytetty, palautetaan merkkijonoista vain ne osat,
joihin säännöllinen lauseke täsmää.

### replace

`replace (regex korvaava)+`

Lukee sisääntulovirrasta merkkijonoarvoja ja työntää ne ulostulovirtaan siten, että niihin on tehty annetut
korvaukset järjestyksessä. Käyttää sisäisesti Javan `String.replaceAll`-metodia.

### split

`split [-s regex] merkkijono`

Palauttaa listan, jossa merkkijono on jaettu osiin annetun erottajan (-s, **separator**) osoittamista kohdista tai
oletuksena välilyöntien perusteella.

### json

`json [-i [-s]] [merkkijono]`

Parsii json-koodin, joka on joko annettu argumenttina tai kaikki sisääntulovirrasta annetut json-koodit (ei molempia).
Palauttaa koodien puut ulostulovirtaan tai, jos valitsin `-i` on annettu, avain-arvo-parit. Lisävalitsin `-s`
määrittää, että avaimet annetaan merkkijonoina listojen sijaan. (TODO parempi selitys)

### list

`list arvo*`

Palauttaa argumentit listana.

### true

`true`

Työntää arvon `true` ulostulovirtaan.

### false

`false`

Työntää arvon `false` ulostulovirtaan.

### random

`random [-boolean|-float|-integer]`

Työntää oletuksena satunnaisen totuusarvon ulostulovirtaan.
Voi myös palauttaa kokonaisluvun tai liukuluvun merkkijonona.

### expr

`expr merkkijono+`

Yhdistää merkkijonot ja antaa lopputuloksen laskimelle, joka palauttaa vastauksen ulostulovirtaan.
Tukee tällä hetkellä vain kokonaislukuja.

### test

`test arvo [-not](-eq|-strong_eq|-weak_eq|-matches|-lt|-le|-gt|-ge) arvo`

Vertailee kahta arvoa annetulla operaattorilla. Valitsin `-not` tekee vertailusta käänteisen. Sen on oltava
kiinni isäntävalitsimessa (ei välilyöntiä).

- `-eq` on paras yksinkertainen ekvivalenssioperaattori. Se muuttaa luvut merkkijonoiksi ja toisin päin,
mutta vaatii muuten tyypeiltä yhtenevyyttä.
- `-strong_eq` vaatii, että kaikki tyypit yhtenevät, myös luvut ja merkkijonot.
- `-weak_eq` muuttaa operandit merkkijonoiksi ja vertailee niitä.
- `-matches` vertailee ensimmäistä operandia toiseen, jonka se olettaa olevan säännöllinen lauseke.

### head

`head [määrä]`

Lukee yhden arvon (tai argumenttina annetun määrän arvoja) ja työntää sen/ne ulostulovirtaan.

### tail

`tail [määrä]`

Lukee kaikki arvot sisääntulovirrasta ja palauttaa viimeisen arvon (tai viimeiset argumenttina annettu määrä arvoa).

### write

`write tiedoston_nimi`

Lukee kaiken sisääntulovirrasta, muuttaa arvot merkkijonoiksi ja kirjoittaa ne annettuun tiedostoon.

### cat

`cat tiedosto*`

Lukee annetut tiedostot rivi kerrallaan ja työntää rivit ulostulovirtaan.

### wcat

`wcat ([-O tiedosto] [-U user_agent] osoite)*`

Lataa tiedostot annetuista Internet-osoitteista mahdollisesti annetuilla user agenteilla
ja kirjoittaa ne annettuihin tiedostoihin (tai oletuksena rivi kerrallaan ulostulovirtaan).

### exec

`exec komento argumentit*`

Suorittaa annetun ulkoisen komennon annetuilla argumenteilla. Jos komennolle ei halua antaa syötettä tai sen
ulostuloa ei halua, voi sen putkittaa nimettömälle funktiolle:

`{} | exec komento argumentit | {}`