# Use an openjdk base image
FROM openjdk:8-jre-alpine

# Set environment variables
ENV H2_VERSION=2.1.214
ENV H2_DOWNLOAD_URL=https://github.com/h2database/h2database/releases/download/version-2.1.214/h2-2022-06-13.zip
ENV H2_DATA_PATH=/opt/h2-data
ENV SCHEMA_FILE=/opt/schema.sql

# Create data directory
RUN mkdir -p ${H2_DATA_PATH}

# Download H2
ARG H2_DOWNLOAD_URL
RUN wget ${H2_DOWNLOAD_URL} -O /tmp/h2.zip

# Calculate SHA1 and compare with given checksum
RUN if [ "$(sha1sum /tmp/h2.zip | awk '{ print $1 }')" != "5ff027217098bf6c800ef96b98f3a381b320e53d" ]; then exit 1; fi

# Extract H2
RUN unzip /tmp/h2.zip -d /opt && \
    rm /tmp/h2.zip

# Copy schema file into the container
COPY schema.sql ${SCHEMA_FILE}

# Expose the H2 ports
EXPOSE 8082
EXPOSE 9092

# Set the working directory
WORKDIR /opt/h2

# Start the H2 server with file-based database and run the schema file
CMD sh -c "java -cp /opt/h2/bin/h2-${H2_VERSION}.jar org.h2.tools.RunScript -url jdbc:h2:/opt/h2-data/testdb -user sa -script ${SCHEMA_FILE} && java -cp /opt/h2/bin/h2-${H2_VERSION}.jar org.h2.tools.Server -web -webAllowOthers -tcp -tcpAllowOthers -baseDir /opt/h2-data"
