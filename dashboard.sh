#!/bin/sh -ex

# A hackathon script to run the translation stats to update the new mode Dashboard.
locale=$1
echo $locale
file="$1_tap_data.txt"
echo $file
gsutil cp "gs://tap-data/fms=$1__locale=$1__use_staged_content=1" $file
python hackathon_script.py "$1"
filename="$1_translations.csv"
echo $filename
bq --project_id='khanacademy.org:deductive-jet-827' load --source_format=CSV translation_progress.tap_data ./$filename tap_run_date:TIMESTAMP,locale:STRING,content_type:STRING,slug:STRING,translatable_word_count:NUMERIC,word_count:NUMERIC,translated_word_count:NUMERIC,approved_word_count:NUMERIC