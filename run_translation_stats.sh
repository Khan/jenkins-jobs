#!/bin/sh -ex

# A hackathon script to run the translation stats to update the new mode Dashboard.
# Parameters passed: locale : The locale for which this job is going to run the translation stats.

locale=$1
echo $locale
file="$1_tap_data.txt"
echo $file

# Copy all the fms content file names
gsutil ls -al gs://tap-data/fms=$1__locale=$1__use_staged_content=1 > $file

# Extract file names and copy the Tap data
cat $file | awk '{system("gsutil cp " $3 " " "fms=" $1 "_" $2)}'

# Convert the FMS TAP data to CSV
python tap_stats_to_csv_script.py "$1"
filename="$1_translations.csv"
echo $filename

# Upload the newly created csv TAP data to Bigquery
bq --project_id='khanacademy.org:deductive-jet-827' load --source_format=CSV translation_progress.tap_data $filename tap_run_date:TIMESTAMP,locale:STRING,content_type:STRING,slug:STRING,translatable_word_count:NUMERIC,word_count:NUMERIC,translated_word_count:NUMERIC,approved_word_count:NUMERIC

# Remove all the files that are no longer needed
rm -rf $filename $file
rm -rf *fms=*